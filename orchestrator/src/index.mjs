import fs from "node:fs/promises";
import path from "node:path";
import { loadConfig } from "./config.mjs";
import { Store, utcDay } from "./store.mjs";
import { budgetStatus, isUsageLimitError } from "./policy.mjs";
import { GitHub } from "./github.mjs";
import { Discord } from "./discord.mjs";
import { WorkspaceManager } from "./workspace.mjs";
import { CodexAppServer, agentText } from "./codex.mjs";

const config = loadConfig();
const store = new Store(config.statePath);
const github = new GitHub(config);
const discord = new Discord(config);
const workspaces = new WorkspaceManager(config);
const active = new Map();
const observedUsage = new Map();

const log = (event, fields = {}) => console.log(JSON.stringify({ timestamp: new Date().toISOString(), event, ...fields }));
const approvalId = (pr) => `pr-${pr.number}`;
const redact = (value) => {
  let text = String(value);
  const secrets = [config.githubToken, config.discordBotToken, config.discordWebhookUrl, process.env.AWS_ACCESS_KEY_ID, process.env.AWS_SECRET_ACCESS_KEY, process.env.AWS_SESSION_TOKEN].filter(Boolean);
  for (const secret of secrets) text = text.replaceAll(secret, "[REDACTED]");
  return text;
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
    await discord.report(`🤖 **#${issue.number} ${issue.title}** 3인 개발 회의를 시작합니다.\n브랜치: \`${branch}\`\n순서: 아키 설계 → 볼트 구현 → 센티넬 독립 검토`);

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
      role: "아키",
      meter: "architect",
      sandboxMode: "read-only",
      instructions: "You are 아키, the senior systems architect. Analyze only; never edit files, push, deploy, or request credentials. Produce a concise implementation contract for 볼트 and explicit review criteria for 센티넬.",
      prompt: `${workflow}\n\n# 역할: 아키 · 설계자\n${issuePrompt}\n\n저장소를 읽고 구현 계약을 작성하세요. 범위, 영향 파일, 설계 결정, 테스트, 보안·UX 위험, 완료 기준을 포함하세요. 볼트가 바로 구현하고 센티넬이 독립 검증할 수 있을 정도로 구체적으로 작성하세요.`,
    });
    await store.update((state) => { state.jobs[key].plan = plan; });
    await discord.personaReport("architect", `#${issue.number} 구현 계약`, plan);

    await store.update((state) => { state.jobs[key].status = "implementing"; });
    let builderReport = await runRole({
      role: "볼트",
      meter: "builder-initial",
      sandboxMode: "workspace-write",
      instructions: "You are 볼트, the implementation engineer. Follow 아키's contract and repository instructions. Edit and test inside the workspace, commit locally, and never push, merge, deploy, or access credentials.",
      prompt: `${workflow}\n\n# 역할: 볼트 · 구현자\n${issuePrompt}\n\n# 아키의 구현 계약\n${plan}\n\n계약을 구현하고 관련 테스트를 실행하세요. 완료 후 변경 파일, 테스트 증거, 남은 위험을 보고하고 로컬 커밋을 만드세요.`,
    });
    await discord.personaReport("builder", `#${issue.number} 구현 보고`, builderReport);

    let approved = false;
    let finalReview = "";
    for (let round = 1; round <= config.maxReviewRounds; round += 1) {
      await store.update((state) => { state.jobs[key].status = "reviewing"; state.jobs[key].reviewRound = round; });
      finalReview = await runRole({
        role: "센티넬",
        meter: `reviewer-${round}`,
        sandboxMode: "read-only",
        instructions: "You are 센티넬, an independent quality and security reviewer. Never edit files. Inspect the actual diff and test evidence, challenge assumptions, and fail closed. End with exactly VERDICT: APPROVE or VERDICT: CHANGES_REQUIRED.",
        prompt: `# 역할: 센티넬 · 독립 검토자\n${issuePrompt}\n\n# 아키의 계약\n${plan}\n\n# 볼트의 보고\n${builderReport}\n\n실제 git diff와 테스트를 독립적으로 확인하세요. 기능 정확성, 회귀, 보안, 개인정보, 자동차 UX, 테스트 증거를 검토하고 구체적인 발견사항을 작성하세요. 마지막 줄은 반드시 VERDICT: APPROVE 또는 VERDICT: CHANGES_REQUIRED 중 하나여야 합니다.`,
      });
      await store.update((state) => { state.jobs[key].reviews ??= []; state.jobs[key].reviews.push({ round, report: finalReview }); });
      await discord.personaReport("reviewer", `#${issue.number} 독립 검토 · ${round}차`, finalReview);
      approved = /VERDICT:\s*APPROVE\s*$/im.test(finalReview) && !/VERDICT:\s*CHANGES_REQUIRED/im.test(finalReview);
      if (approved) break;
      if (round === config.maxReviewRounds) throw new Error(`센티넬 검토를 ${config.maxReviewRounds}회 통과하지 못했습니다.`);

      await store.update((state) => { state.jobs[key].status = "reworking"; });
      builderReport = await runRole({
        role: "볼트",
        meter: `builder-rework-${round}`,
        sandboxMode: "workspace-write",
        instructions: "You are 볼트, the implementation engineer. Address every blocking finding from 센티넬, rerun tests, and commit locally. Never push, merge, deploy, or access credentials.",
        prompt: `# 역할: 볼트 · 재작업\n${issuePrompt}\n\n# 아키의 계약\n${plan}\n\n# 센티넬의 반려 의견\n${finalReview}\n\n각 지적을 수정하고 테스트를 다시 실행하세요. 무엇을 어떻게 해결했는지 근거와 함께 보고하고 로컬 커밋을 만드세요.`,
      });
      await discord.personaReport("builder", `#${issue.number} 검토 반영 · ${round}차`, builderReport);
    }

    await store.update((state) => { state.jobs[key].status = "publishing"; });
    const commit = await workspaces.ensureCommit(workspace, issue);
    await workspaces.push(workspace, branch);
    const pr = await github.createPullRequest(branch, `[Agent] ${issue.title}`, `Closes #${issue.number}\n\nThree-role autonomous workflow completed: 아키 architecture, 볼트 implementation, 센티넬 independent approval. Merge is gated by Discord approval.\n\nCommit: ${commit}\n\nReviewer verdict:\n${finalReview.slice(-1200)}`);
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
  if (action === "approve") {
    try {
      const result = await github.mergePullRequest(approval.pr);
      await store.update((state) => { state.approvals[id].status = "approved"; state.approvals[id].decidedBy = userId; state.jobs[String(approval.issue)].status = result.merged ? "merged" : "merge_failed"; });
      await discord.report(result.merged ? `🚀 PR #${approval.pr} 병합 완료 — 승인자 <@${userId}>` : `❌ PR #${approval.pr} 병합 실패: ${redact(result.message)}`);
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
  const usage = store.state.usage[utcDay()] ?? { tokens: 0, estimatedUsd: 0 };
  await discord.reply(interaction, `**Social Drive AI 상태**\n실행 중: ${active.size}/${config.maxParallel}\n전체 작업: ${Object.keys(store.state.jobs).length}\n병합 승인 대기: ${pending.length}\n오늘 토큰: ${usage.tokens}\n오늘 예상 비용: $${Number(usage.estimatedUsd).toFixed(2)}`);
});

onAsync("testApproval", async ({ userId }) => {
  const id = `test-${Date.now().toString(36)}`;
  await store.update((state) => {
    state.approvals[id] = { id, type: "dry-run", createdAt: Date.now(), expiresAt: Date.now() + config.approvalTtlMs, status: "pending", requestedBy: userId };
  });
  await discord.personaReport("architect", "회의 테스트 · 구현 계약", "요구사항을 작은 단위로 분리하고, 외부 변경 없이 승인 게이트의 메시지·권한·감사 흐름만 검증하겠습니다.");
  await discord.personaReport("builder", "회의 테스트 · 실행 결과", "건식 승인 요청을 생성했습니다. GitHub, AWS, 데이터에는 어떤 변경도 수행하지 않았습니다.");
  await discord.personaReport("reviewer", "회의 테스트 · 독립 검토", "승인 ID, 만료 시간, 승인자 권한, 중복 클릭 방지를 확인했습니다.\n\nVERDICT: APPROVE");
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
discord.once("ready", () => void discord.report(`🟢 **개발자 시작**\n상태: GitHub Issue 감시 중\n최대 병렬 Agent: ${config.maxParallel}\n대상: ${config.githubOwner}/${config.githubRepo}`));
await poll().catch((error) => log("poll_failed", { error: error.message }));
setInterval(() => poll().catch((error) => log("poll_failed", { error: error.message })), config.pollIntervalMs);

