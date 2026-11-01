import fs from "node:fs/promises";
import path from "node:path";
import { loadConfig } from "./config.mjs";
import { Store, utcDay } from "./store.mjs";
import { budgetStatus, isUsageLimitError } from "./policy.mjs";
import { GitHub } from "./github.mjs";
import { Discord } from "./discord.mjs";
import { WorkspaceManager } from "./workspace.mjs";
import { CodexAppServer, agentText } from "./codex.mjs";
import { AwsDeployer, formatChangeSummary } from "./aws.mjs";

const config = loadConfig();
const store = new Store(config.statePath);
const github = new GitHub(config);
const discord = new Discord(config);
const workspaces = new WorkspaceManager(config);
const aws = new AwsDeployer(config);
const active = new Map();
const observedUsage = new Map();
let deploymentActive = false;

const log = (event, fields = {}) => console.log(JSON.stringify({ timestamp: new Date().toISOString(), event, ...fields }));
const approvalId = (pr) => `pr-${pr.number}`;
const redact = (value) => {
  let text = String(value);
  const secrets = [config.githubToken, config.discordBotToken, config.discordWebhookUrl, process.env.AWS_ACCESS_KEY_ID, process.env.AWS_SECRET_ACCESS_KEY, process.env.AWS_SESSION_TOKEN].filter(Boolean);
  for (const secret of secrets) text = text.replaceAll(secret, "[REDACTED]");
  return text.replace(/\b\d{12}\b/g, "[AWS_ACCOUNT]").replace(/\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g, "[AWS_KEY]");
};
const onAsync = (event, handler) => discord.on(event, (...args) => void handler(...args).catch((error) => log(`${event}_failed`, { error: redact(error.message) })));

async function accountUsage(issueNumber, event) {
  const usage = event?.params?.usage ?? event?.params?.turn?.usage;
  if (!usage) return;
  const total = Number(usage.totalTokens ?? usage.total_tokens ?? 0);
  if (!total) return;
  const previous = observedUsage.get(issueNumber) ?? 0;
  const tokens = Math.max(0, total - previous);
  observedUsage.set(issueNumber, Math.max(previous, total));
  if (!tokens) return;
  await store.update((state) => {
    const day = utcDay(); const current = state.usage[day] ?? { tokens: 0, estimatedUsd: 0 };
    current.tokens += tokens;
    current.estimatedUsd = current.tokens / 1_000_000 * config.estimatedUsdPerMillionTokens;
    state.usage[day] = current;
  });
}

async function runIssue(issue) {
  const key = String(issue.number);
  try {
    await store.update((state) => { state.jobs[key] = { ...(state.jobs[key] ?? {}), issue: issue.number, title: issue.title, status: "preparing", attempts: (state.jobs[key]?.attempts ?? 0) + 1, error: null, nextRetryAt: null, updatedAt: new Date().toISOString() }; });
    const { workspace, branch } = await workspaces.prepare(issue);
    await store.update((state) => { state.jobs[key].status = "running"; state.jobs[key].workspace = workspace; state.jobs[key].branch = branch; });
    await discord.report(`🤖 **#${issue.number} ${issue.title}** 3인 개발 회의를 시작합니다.\n브랜치: \`${branch}\`\n순서: 아키 기획·설계 → 픽시 디자인·개발 → 센티넬 독립 검토`);

    const workflow = await fs.readFile(path.join(config.sourceRepository, "WORKFLOW.md"), "utf8").catch(() => "");
    const issuePrompt = `Issue #${issue.number}: ${issue.title}\n\n${issue.body ?? ""}`;
    const runRole = async ({ role, meter, prompt, sandboxMode, instructions }) => {
      const codex = new CodexAppServer(config, {
        workspace,
        onEvent: (event) => { log("codex_event", { issue: issue.number, role, type: event.event }); void accountUsage(`${issue.number}:${meter}`, event); },
        onApproval: async (method) => {
          await discord.report(`⛔ #${issue.number} ${role}가 차단된 권한을 요청했습니다: \`${method}\`. 요청은 자동 거부됩니다.`);
          return { decision: "decline" };
        },
      });
      const turn = await codex.start(prompt, { sandboxMode, networkAccess: sandboxMode === "workspace-write", developerInstructions: instructions });
      return redact(agentText(turn) || "결과 메시지가 없습니다.");
    };

    await store.update((state) => { state.jobs[key].status = "planning"; });
    const plan = await runRole({
      role: "아키(기획·설계)",
      meter: "architect",
      sandboxMode: "read-only",
      instructions: "You are 아키, the senior product planner and systems architect. Analyze only; never edit files, push, deploy, or request credentials. Produce one concise product and technical contract for 픽시, with explicit review criteria for 센티넬.",
      prompt: `${workflow}\n\n# 역할: 아키(기획·설계)\n${issuePrompt}\n\n저장소와 요구사항을 읽고 통합 기획·설계 계약을 작성하세요. 사용자 문제와 여정, 우선순위, 범위와 비범위, 엣지 케이스, 영향 파일, 데이터·상태 흐름, 인터페이스, 설계 결정, 테스트, 보안·성능 위험, 측정 가능한 완료 기준을 포함하세요. 픽시가 디자인과 구현을 바로 진행하고 센티넬이 독립 검증할 수 있을 정도로 구체적이어야 합니다.`,
    });
    await store.update((state) => { state.jobs[key].plan = plan; });
    await discord.personaReport("architect", `#${issue.number} 기획·설계 계약`, plan);

    await store.update((state) => { state.jobs[key].status = "implementing"; });
    let implementationReport = await runRole({
      role: "픽시(디자인·개발)",
      meter: "designer-builder-initial",
      sandboxMode: "workspace-write",
      instructions: "You are 픽시, the senior female product designer and implementation engineer. Follow 아키's product and technical contract and repository instructions. Define the UX before editing, then implement and test inside the workspace. Commit locally, and never push, merge, deploy, or access credentials.",
      prompt: `${workflow}\n\n# 역할: 픽시(디자인·개발)\n${issuePrompt}\n\n# 아키의 기획·설계 계약\n${plan}\n\n먼저 기존 디자인 시스템을 기준으로 사용자 흐름, 화면 계층, 컴포넌트 상태, 로딩·빈 화면·오류, 반응형, 접근성, 모션, 문구와 시각 완성도 기준을 정한 뒤 곧바로 구현하세요. UI가 없는 작업이면 사용자 경험과 운영 가시성을 설계하고 구현하세요. 관련 테스트를 실행하고 변경 파일, 테스트 증거, 디자인 반영 증거, 남은 위험을 보고한 뒤 로컬 커밋을 만드세요.`,
    });
    await discord.personaReport("designer", `#${issue.number} 디자인·개발 보고`, implementationReport);

    let approved = false;
    let finalReview = "";
    for (let round = 1; round <= config.maxReviewRounds; round += 1) {
      await store.update((state) => { state.jobs[key].status = "reviewing"; state.jobs[key].reviewRound = round; });
      finalReview = await runRole({
        role: "센티넬(검토자)",
        meter: `reviewer-${round}`,
        sandboxMode: "read-only",
        instructions: "You are 센티넬, an independent quality and security reviewer. Never edit files. Inspect the actual diff and test evidence, challenge assumptions, and fail closed. End with exactly VERDICT: APPROVE or VERDICT: CHANGES_REQUIRED.",
        prompt: `# 역할: 센티넬(검토자)\n${issuePrompt}\n\n# 아키의 기획·설계 계약\n${plan}\n\n# 픽시의 디자인·개발 보고\n${implementationReport}\n\n실제 git diff와 테스트를 독립적으로 확인하세요. 기능 정확성, 기획 수용 기준, 디자인 완성도, 모든 UI 상태, 반응형·접근성, 회귀, 보안, 개인정보, 자동차 UX, 테스트 증거를 검토하고 구체적인 발견사항을 작성하세요. 마지막 줄은 반드시 VERDICT: APPROVE 또는 VERDICT: CHANGES_REQUIRED 중 하나여야 합니다.`,
      });
      await store.update((state) => { state.jobs[key].reviews ??= []; state.jobs[key].reviews.push({ round, report: finalReview }); });
      await discord.personaReport("reviewer", `#${issue.number} 독립 검토 · ${round}차`, finalReview);
      approved = /VERDICT:\s*APPROVE\s*$/im.test(finalReview) && !/VERDICT:\s*CHANGES_REQUIRED/im.test(finalReview);
      if (approved) break;
      if (round === config.maxReviewRounds) throw new Error(`센티넬 검토를 ${config.maxReviewRounds}회 통과하지 못했습니다.`);

      await store.update((state) => { state.jobs[key].status = "reworking"; });
      implementationReport = await runRole({
        role: "픽시(디자인·개발)",
        meter: `designer-builder-rework-${round}`,
        sandboxMode: "workspace-write",
        instructions: "You are 픽시, the product designer and implementation engineer. Address every blocking functional and design finding from 센티넬, rerun tests, and commit locally. Never push, merge, deploy, or access credentials.",
        prompt: `# 역할: 픽시(디자인·개발) · 재작업\n${issuePrompt}\n\n# 아키의 기획·설계 계약\n${plan}\n\n# 센티넬의 반려 의견\n${finalReview}\n\n각 지적을 수정하고 테스트를 다시 실행하세요. 무엇을 어떻게 해결했는지 기능·디자인 근거와 함께 보고하고 로컬 커밋을 만드세요.`,
      });
      await discord.personaReport("designer", `#${issue.number} 검토 반영 · ${round}차`, implementationReport);
    }

    await store.update((state) => { state.jobs[key].status = "publishing"; });
    const commit = await workspaces.ensureCommit(workspace, issue);
    await workspaces.push(workspace, branch);
    const pr = await github.createPullRequest(branch, `[Agent] ${issue.title}`, `Closes #${issue.number}\n\nThree-role autonomous workflow completed: 아키 product planning and architecture, 픽시 product design and implementation, 센티넬 independent approval. Merge is gated by Discord approval.\n\nCommit: ${commit}\n\nReviewer verdict:\n${finalReview.slice(-1200)}`);
    const id = approvalId(pr);
    await store.update((state) => {
      state.jobs[key] = { ...state.jobs[key], status: "awaiting_merge_approval", pr: pr.number, prUrl: pr.html_url, commit, updatedAt: new Date().toISOString() };
      state.approvals[id] = { id, type: "merge", issue: issue.number, pr: pr.number, createdAt: Date.now(), expiresAt: Date.now() + config.approvalTtlMs, status: "pending" };
    });
    await github.removeReadyLabel(issue.number).catch(() => {});
    await github.comment(issue.number, `Codex completed the implementation: ${pr.html_url}\n\nMerge is waiting for Discord approval.`);
    await discord.requestApproval({ id, title: `PR #${pr.number} 병합`, description: `#${issue.number} ${issue.title}\n\nCodex 구현과 검증이 끝났습니다. PR을 검토한 뒤 버튼을 선택하세요.`, url: pr.html_url });
  } catch (error) {
    log("job_failed", { issue: issue.number, error: redact(error.message) });
    const usageLimited = isUsageLimitError(error);
    await store.update((state) => {
      const job = state.jobs[key] ?? { issue: issue.number, attempts: 1 };
      job.status = usageLimited ? "paused_usage_limit" : job.attempts <= config.maxRetries ? "retry" : "failed";
      job.error = redact(error.message);
      job.nextRetryAt = usageLimited ? Date.now() + config.usageLimitCooldownMs : null;
      job.updatedAt = new Date().toISOString(); state.jobs[key] = job;
    });
    if (usageLimited) await discord.report(`⏸️ **#${issue.number} 사용량 한도로 일시 정지**\n작업 상태는 보존됩니다. ${Math.round(config.usageLimitCooldownMs / 60_000)}분 후 자동 재시도합니다.`);
    else await discord.report(`❌ **#${issue.number} 작업 실패**\n${redact(error.message).slice(0, 1500)}`);
  } finally { active.delete(key); }
}

async function poll() {
  await expireApprovals();
  const usage = store.state.usage[utcDay()] ?? { tokens: 0, estimatedUsd: 0 };
  const budget = budgetStatus(config, usage);
  if (!budget.allowed) { log("budget_paused", { reason: budget.reason, usage }); return; }
  const issues = await github.listReadyIssues();
  for (const issue of issues) {
    if (active.size >= config.maxParallel) break;
    const key = String(issue.number); const job = store.state.jobs[key];
    if (job?.nextRetryAt && Date.now() < job.nextRetryAt) continue;
    if (active.has(key) || ["awaiting_merge_approval", "merged", "rejected", "failed"].includes(job?.status)) continue;
    active.set(key, true); void runIssue(issue);
  }
}

async function expireApprovals() {
  const now = Date.now();
  const expired = [];
  await store.update((state) => {
    for (const approval of Object.values(state.approvals)) {
      if (approval.status === "pending" && now > approval.expiresAt) {
        approval.status = "expiring";
        expired.push({ ...approval });
      }
    }
  });
  for (const approval of expired) {
    try {
      if (approval.type === "aws-deploy") await aws.reject(approval);
      await store.update((state) => { state.approvals[approval.id].status = "expired"; });
      await discord.report(`⌛ 승인 만료: \`${approval.id}\`${approval.type === "aws-deploy" ? " — 미실행 변경 세트를 삭제했습니다." : ""}`);
    } catch (error) {
      await store.update((state) => { state.approvals[approval.id].status = "failed"; state.approvals[approval.id].error = redact(error.message); });
      await discord.report(`⚠️ 만료 승인 정리 실패: \`${approval.id}\`\n${redact(error.message).slice(0, 1200)}`);
    }
  }
}

async function recoverAwsApprovals() {
  const interrupted = Object.values(store.state.approvals).filter((item) => item.type === "aws-deploy" && item.status === "processing");
  for (const approval of interrupted) {
    deploymentActive = true;
    try {
      const recovery = await aws.recover(approval);
      if (recovery.action === "pending") {
        await store.update((state) => { state.approvals[approval.id].status = "pending"; delete state.approvals[approval.id].processingBy; });
        await discord.requestApproval({
          id: approval.id,
          title: `복구된 AWS dev 배포 · ${approval.changeSetType}`,
          description: `재시작 전에 실행되지 않은 변경 세트입니다. 다시 결정해 주세요.\n\n${formatChangeSummary(approval.changes ?? [])}`,
        });
      } else {
        await store.update((state) => { state.approvals[approval.id].status = "approved"; });
        await discord.report(`✅ **재시작 후 AWS 배포 복구 완료**\n승인 ID: \`${approval.id}\`\n상태: \`${recovery.result.stackStatus}\``);
      }
    } catch (error) {
      await store.update((state) => { state.approvals[approval.id].status = "failed"; state.approvals[approval.id].error = redact(error.message); });
      await discord.report(`❌ **중단된 AWS 배포 복구 실패**\n승인 ID: \`${approval.id}\`\n${redact(error.message).slice(0, 1200)}`);
    } finally { deploymentActive = false; }
  }
}

async function prepareAwsDeployment({ sourceSha = null, pr = null, issue = null, requestedBy = null } = {}) {
  if (!config.awsDeployEnabled) throw new Error("AWS deployment gateway is disabled");
  if (deploymentActive) return void discord.report("⏳ AWS 변경 세트를 이미 준비하거나 실행 중입니다.");
  const pending = Object.values(store.state.approvals).find((item) => item.type === "aws-deploy" && ["pending", "processing"].includes(item.status));
  if (pending) return void discord.report(`⏳ AWS 배포 승인 대기 중입니다: \`${pending.id}\``);
  deploymentActive = true;
  const requestId = `deploy-${Date.now().toString(36)}`;
  try {
    await discord.report("☁️ **AWS dev 배포 후보 준비 시작**\nmain의 고정된 커밋에서 패키징하고 CloudFormation 변경 세트만 생성합니다. 아직 리소스는 배포하지 않습니다.");
    const preparedWorkspace = await workspaces.prepareDeployment(requestId, sourceSha);
    const prepared = await aws.prepare({ ...preparedWorkspace, sourceSha: preparedWorkspace.sha, pr, issue, requestedBy });
    if (prepared.noChanges) {
      await aws.reject(prepared).catch(() => {});
      await discord.report(`ℹ️ **AWS 변경 없음**\n커밋: \`${preparedWorkspace.sha.slice(0, 12)}\`\n실행할 변경 세트가 없습니다.`);
      return;
    }
    const id = prepared.id;
    await store.update((state) => {
      state.approvals[id] = { ...prepared, createdAt: Date.now(), expiresAt: Date.now() + config.approvalTtlMs, status: "pending" };
      if (issue && state.jobs[String(issue)]) state.jobs[String(issue)].status = "awaiting_deploy_approval";
    });
    const risk = prepared.highRisk ? "\n\n⚠️ 삭제 또는 교체 가능성이 포함되어 있습니다. 특히 주의해서 확인하세요." : "";
    await discord.requestApproval({
      id,
      title: `AWS dev 배포 · ${prepared.changeSetType}`,
      description: `커밋 \`${prepared.sourceSha.slice(0, 12)}\`의 변경 세트입니다. 승인해야만 실행됩니다.\n\n${formatChangeSummary(prepared.changes)}${risk}`,
    });
  } catch (error) {
    await discord.report(`❌ **AWS 변경 세트 준비 실패**\n${redact(error.message).slice(0, 1500)}`);
    throw error;
  } finally {
    deploymentActive = false;
  }
}

onAsync("approval", async ({ action, id, userId }) => {
  let approval;
  await store.update((state) => {
    const candidate = state.approvals[id];
    if (candidate?.status !== "pending") return;
    candidate.status = "processing";
    candidate.processingBy = userId;
    approval = { ...candidate };
  });
  if (!approval) return void discord.report(`⚠️ 이미 처리됐거나 유효하지 않은 승인입니다: \`${id}\``);
  if (Date.now() > approval.expiresAt) action = "reject";
  if (approval.type === "dry-run") {
    await store.update((state) => { state.approvals[id].status = action === "approve" ? "approved" : "rejected"; state.approvals[id].decidedBy = userId; });
    await discord.report(action === "approve" ? `✅ **건식 승인 테스트 성공**\n승인 ID: \`${id}\`\n승인자: <@${userId}>\n외부 변경: 없음` : `🛑 **건식 거부 테스트 성공**\n승인 ID: \`${id}\`\n거부자: <@${userId}>\n외부 변경: 없음`);
    return;
  }
  if (approval.type === "aws-deploy") {
    deploymentActive = true;
    try {
      if (action === "reject") {
        await aws.reject(approval);
        await store.update((state) => { state.approvals[id].status = "rejected"; state.approvals[id].decidedBy = userId; });
        await discord.report(`🛑 **AWS 배포 거부**\n변경 세트 \`${id}\`를 삭제했습니다. AWS 리소스는 변경되지 않았습니다.\n결정자: <@${userId}>`);
        return;
      }
      await discord.report(`🚀 **AWS dev 배포 승인됨**\n승인자: <@${userId}>\n변경 세트 \`${id}\` 실행을 시작합니다.`);
      const result = await aws.execute(approval, (message) => discord.report(`☁️ ${message}`));
      await store.update((state) => {
        state.approvals[id].status = "approved"; state.approvals[id].decidedBy = userId;
        if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "deployed";
      });
      const smoke = result.smoke.attempted ? `HTTP ${result.smoke.status} · 인증 차단 정상` : "API URL 출력 없음";
      await discord.report(`✅ **AWS dev 배포 완료**\n상태: \`${result.stackStatus}\`\n보안 스모크 테스트: ${smoke}\n출력 항목: ${result.outputKeys.map((key) => `\`${key}\``).join(", ") || "없음"}`);
    } catch (error) {
      await store.update((state) => { state.approvals[id].status = "failed"; state.approvals[id].error = redact(error.message); });
      await discord.report(`❌ **AWS 배포 처리 실패**\n승인 ID: \`${id}\`\n${redact(error.message).slice(0, 1500)}`);
    } finally {
      deploymentActive = false;
    }
    return;
  }
  if (action === "approve") {
    try {
      const result = await github.mergePullRequest(approval.pr);
      await store.update((state) => { state.approvals[id].status = "approved"; state.approvals[id].decidedBy = userId; state.jobs[String(approval.issue)].status = result.merged ? "merged" : "merge_failed"; });
      await discord.report(result.merged ? `🚀 PR #${approval.pr} 병합 완료 — 승인자 <@${userId}>` : `❌ PR #${approval.pr} 병합 실패: ${redact(result.message)}`);
      if (result.merged) {
        const files = await github.listPullRequestFiles(approval.pr);
        if (files.some((file) => file.filename === "infra" || file.filename.startsWith("infra/"))) {
          void prepareAwsDeployment({ sourceSha: result.sha, pr: approval.pr, issue: approval.issue, requestedBy: userId })
            .catch((error) => log("automatic_deploy_prepare_failed", { error: redact(error.message) }));
        }
      }
    } catch (error) {
      await store.update((state) => { state.approvals[id].status = "failed"; state.approvals[id].error = redact(error.message); });
      await discord.report(`❌ PR #${approval.pr} 병합 요청 실패\n${redact(error.message)}`);
    }
  } else {
    await store.update((state) => { state.approvals[id].status = "rejected"; state.approvals[id].decidedBy = userId; state.jobs[String(approval.issue)].status = "rejected"; });
    await discord.report(`🛑 PR #${approval.pr} 병합 거부 — <@${userId}>`);
  }
});

discord.on("gatewayError", (error) => log("discord_gateway_error", { error: redact(error.message) }));

onAsync("claim", async ({ userId }) => {
  discord.authorize(userId);
  await store.update((state) => {
    state.authorizedDiscordUsers ??= [];
    if (!state.authorizedDiscordUsers.includes(userId)) state.authorizedDiscordUsers.push(userId);
  });
  await discord.report(`🔐 **Discord 승인자 등록 완료**\n승인자: <@${userId}>`);
});

onAsync("status", async ({ interaction }) => {
  const pending = Object.values(store.state.approvals).filter((item) => item.status === "pending");
  const mergePending = pending.filter((item) => item.type === "merge").length;
  const deployPending = pending.filter((item) => item.type === "aws-deploy").length;
  const usage = store.state.usage[utcDay()] ?? { tokens: 0, estimatedUsd: 0 };
  await discord.reply(interaction, `**Social Drive AI 상태**\n실행 중: ${active.size}/${config.maxParallel}\n전체 작업: ${Object.keys(store.state.jobs).length}\n병합 승인 대기: ${mergePending}\nAWS 배포 승인 대기: ${deployPending}\nAWS 작업 중: ${deploymentActive ? "예" : "아니오"}\n오늘 토큰: ${usage.tokens}\n오늘 예상 비용: $${Number(usage.estimatedUsd).toFixed(2)}`);
});

onAsync("deploy", async ({ userId }) => {
  await prepareAwsDeployment({ requestedBy: userId });
});

onAsync("testApproval", async ({ userId }) => {
  const id = `test-${Date.now().toString(36)}`;
  await store.update((state) => {
    state.approvals[id] = { id, type: "dry-run", createdAt: Date.now(), expiresAt: Date.now() + config.approvalTtlMs, status: "pending", requestedBy: userId };
  });
  await discord.personaReport("architect", "회의 테스트 · 기획·설계", "사용자는 승인 ID를 복사하지 않고 버튼 한 번으로 결정할 수 있어야 합니다. 권한, 만료, 중복 처리 방지를 포함한 건식 승인 흐름을 완료 기준으로 설계했습니다.");
  await discord.personaReport("designer", "회의 테스트 · 디자인·개발", "승인과 거부를 색상·아이콘·문구로 구분하고 검토 링크와 영향이 한 화면에서 읽히도록 구성했습니다. GitHub, AWS, 데이터에는 어떤 변경도 수행하지 않았습니다.");
  await discord.personaReport("reviewer", "회의 테스트 · 독립 검토", "기획 기준, 버튼 가독성, 승인 ID, 만료 시간, 승인자 권한, 중복 클릭 방지를 확인했습니다.\n\nVERDICT: APPROVE");
  await discord.requestApproval({ id, title: "건식 승인 게이트 테스트", description: "실제 GitHub·AWS 변경은 발생하지 않습니다. 아래 버튼으로 승인 또는 거부 흐름을 시험하세요." });
});

await store.load();
for (const userId of store.state.authorizedDiscordUsers ?? []) discord.authorize(userId);
try {
  const brandVersion = await discord.brandVersion();
  if (store.state.discordBrandVersion !== brandVersion) {
    await discord.updateBrand();
    await store.update((state) => { state.discordBrandVersion = brandVersion; });
    log("discord_brand_updated", { name: config.discordDisplayName });
  }
} catch (error) {
  log("discord_brand_update_failed", { error: error.message });
}
discord.connect();
discord.once("ready", () => {
  void discord.report(`🟢 **개발자 시작**\n상태: GitHub Issue 감시 중\n최대 병렬 Agent: ${config.maxParallel}\n대상: ${config.githubOwner}/${config.githubRepo}\nAWS dev 승인 게이트: ${config.awsDeployEnabled ? "활성" : "비활성"}`);
  if (config.awsDeployEnabled) void recoverAwsApprovals().catch((error) => log("aws_recovery_failed", { error: redact(error.message) }));
});
await poll().catch((error) => log("poll_failed", { error: error.message }));
setInterval(() => poll().catch((error) => log("poll_failed", { error: error.message })), config.pollIntervalMs);

