import fs from "node:fs/promises";
import path from "node:path";
import { loadConfig } from "./config.mjs";
import { Store, utcDay } from "./store.mjs";
import { budgetStatus, isUsageLimitError } from "./policy.mjs";
import { GitHub } from "./github.mjs";
import { Discord } from "./discord.mjs";
import { WorkspaceManager } from "./workspace.mjs";
import { CodexAppServer, agentText, readCodexAccountState } from "./codex.mjs";
import { AwsDeployer, formatChangeSummary } from "./aws.mjs";
import {
  buildProgressSnapshot,
  discordStageEventKey,
  DISCORD_STAGE_STATUSES,
  managedControlLabels,
  renderAgentStatus,
  renderDiscordStageReport,
  renderProgressComment,
} from "./control-center.mjs";
import { DurableOutbox, enqueueOutbox } from "./outbox.mjs";
import { recordUsage } from "./usage.mjs";
import { recordAccountRateLimit, recordAccountTokenSnapshot, threadTotalTokens } from "./account-usage.mjs";
import { classifyJobFailure, failureNotice, isReviewChangesRequiredError } from "./job-failure.mjs";
import { DashboardWriter } from "./dashboard.mjs";
import { reconcileReadyIssues } from "./ready-queue.mjs";
import { buildVisualReport, visualReportKey } from "./visual-report.mjs";
import {
  activateFollowUp,
  bindApprovalFollowUp,
  mergeRecoveryDecision,
  migrateApprovalFollowUps,
  recordMergeResult,
} from "./follow-up.mjs";

const config = loadConfig();
const store = new Store(config.statePath);
const github = new GitHub(config);
const discord = new Discord(config);
const workspaces = new WorkspaceManager(config);
const aws = new AwsDeployer(config);
const active = new Map();
const observedUsage = new Map();
let deploymentActive = false;
let mergeRecoveryActive = false;
let lastAccountUsageSyncAt = 0;

const log = (event, fields = {}) => console.log(JSON.stringify({ timestamp: new Date().toISOString(), event, ...fields }));
const approvalId = (pr) => `pr-${pr.number}`;
const redact = (value) => {
  let text = String(value);
  const secrets = [config.githubToken, config.discordBotToken, config.discordWebhookUrl, process.env.AWS_ACCESS_KEY_ID, process.env.AWS_SECRET_ACCESS_KEY, process.env.AWS_SESSION_TOKEN].filter(Boolean);
  for (const secret of secrets) text = text.replaceAll(secret, "[REDACTED]");
  return text.replace(/\b\d{12}\b/g, "[AWS_ACCOUNT]").replace(/\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g, "[AWS_KEY]");
};
const onAsync = (event, handler) => discord.on(event, (...args) => void handler(...args).catch((error) => log(`${event}_failed`, { error: redact(error.message) })));

const outbox = new DurableOutbox({
  store,
  maxAttempts: config.outboxMaxAttempts,
  logger: log,
  handlers: {
    "discord-report": ({ content }, entry) => discord.reportOnce(entry.key, content),
    "discord-visual": ({ report }, entry) => discord.visualReportOnce(entry.key, report),
    "github-progress": async ({ issue, body, knownCommentId }) => {
      const commentId = await github.upsertProgressComment(issue, body, knownCommentId);
      await store.update((state) => {
        if (state.jobs[String(issue)]) state.jobs[String(issue)].progressCommentId = commentId;
      });
    },
    "github-labels": ({ issue, labels }) => github.reconcileControlLabels(issue, labels, managedControlLabels()),
    "github-project": ({ snapshot }) => github.syncProjectSnapshot(snapshot),
    "github-follow-up-activation": (payload) => activateFollowUp({
      github,
      store,
      payload,
      currentRepository: `${config.githubOwner}/${config.githubRepo}`,
      readyLabel: config.readyLabel,
    }),
    "github-follow-up-audit": ({ issue, pr, readyLabel }) => github.upsertFollowUpComment(issue, { pr, readyLabel }),
  },
});
const dashboard = new DashboardWriter({
  file: config.dashboardPath,
  store,
  config,
  runtime: () => ({ activeCount: active.size, deploymentActive }),
});
const refreshDashboard = () => dashboard.write().catch((error) => log("dashboard_write_failed", { error: redact(error.message) }));

async function enqueueVisualOutcome(key, input) {
  const job = store.state.jobs[key];
  if (!job?.issue) return;
  await store.update((state) => {
    const current = state.jobs[key];
    if (!current?.issue) return;
    const report = buildVisualReport({ ...input, issue: current.issue, evidenceKind: input.evidenceKind ?? "NONE" }, {
      repository: `${config.githubOwner}/${config.githubRepo}`,
    });
    const eventKey = visualReportKey(report, {
      repository: `${config.githubOwner}/${config.githubRepo}`,
      runId: `${current.issue}:${current.attempts ?? 0}`,
    });
    enqueueOutbox(state, { key: eventKey, destination: "discord-visual", payload: { issue: current.issue, report } });
  });
  void outbox.drain();
}

const queueVisualOutcome = async (key, input) => {
  try { await enqueueVisualOutcome(key, input); }
  catch (error) { log("visual_report_enqueue_failed", { issue: key, error: redact(error.message) }); }
};

const queueDiscordNotice = async (eventKey, content) => {
  try {
    await store.update((state) => {
      enqueueOutbox(state, {
        key: eventKey,
        destination: "discord-report",
        payload: { content: redact(content).slice(0, 1900) },
      });
    });
    void outbox.drain();
  } catch (error) {
    log("discord_notice_enqueue_failed", { eventKey, error: redact(error.message) });
  }
};

const enqueueJobDestinations = (state, key, now = new Date()) => {
  const job = state.jobs[key];
  if (!job?.issue) return;
  const approval = Object.values(state.approvals ?? {}).find((item) => item.status === "pending" && Number(item.issue) === Number(job.issue)) ?? null;
  const usage = state.usage[utcDay(now)] ?? { tokens: 0, estimatedUsd: 0 };
  const snapshot = buildProgressSnapshot(job, { dailyUsage: usage, dailyTokenLimit: config.dailyTokenLimit, approval, now });
  enqueueOutbox(state, { key: `issue:${key}:progress`, destination: "github-progress", payload: { issue: job.issue, body: renderProgressComment(snapshot), knownCommentId: job.progressCommentId ?? null } }, now.getTime());
  enqueueOutbox(state, { key: `issue:${key}:labels`, destination: "github-labels", payload: { issue: job.issue, labels: snapshot.labels } }, now.getTime());
  if (config.githubProjectId) enqueueOutbox(state, { key: `issue:${key}:project`, destination: "github-project", payload: { snapshot } }, now.getTime());
};

async function updateJob(key, mutator) {
  const now = new Date();
  await store.update((state) => {
    const previousStatus = state.jobs[key]?.status;
    mutator(state);
    if (state.jobs[key]) state.jobs[key].updatedAt = now.toISOString();
    const currentStatus = state.jobs[key]?.status;
    if (state.jobs[key]?.issue && currentStatus !== previousStatus && DISCORD_STAGE_STATUSES.has(currentStatus)) {
      const approval = Object.values(state.approvals ?? {}).find((item) => item.status === "pending" && Number(item.issue) === Number(state.jobs[key].issue)) ?? null;
      const usage = state.usage[utcDay(now)] ?? { tokens: 0, estimatedUsd: 0 };
      const snapshot = buildProgressSnapshot(state.jobs[key], { dailyUsage: usage, dailyTokenLimit: config.dailyTokenLimit, approval, now });
      enqueueOutbox(state, {
        key: discordStageEventKey(key, state.jobs[key]),
        destination: "discord-report",
        payload: { content: renderDiscordStageReport(snapshot) },
      }, now.getTime());
    }
    if (["retry", "paused_usage_limit"].includes(previousStatus) && !["retry", "paused_usage_limit", "failed"].includes(state.jobs[key]?.status)) {
      enqueueOutbox(state, {
        key: `recovery:${key}:${state.jobs[key].attempts ?? 0}`,
        destination: "discord-report",
        payload: { content: `✅ **#${state.jobs[key].issue} 자동 복구 완료**\n보존된 작업에서 ${state.jobs[key].status} 단계를 재개했습니다. 중복 PR이나 배포는 만들지 않습니다.` },
      }, now.getTime());
    }
    enqueueJobDestinations(state, key, now);
  });
  void outbox.drain();
  void refreshDashboard();
}

async function accountUsage(issueNumber, event) {
  if (event?.event === "account/usage/read") {
    await store.update((state) => recordAccountTokenSnapshot(state, event.params, { day: utcDay() }));
    void refreshDashboard();
    return;
  }
  if (event?.event === "account/rateLimits/read" || event?.event === "account/rateLimits/updated") {
    await store.update((state) => recordAccountRateLimit(state, event.params, { now: event.emittedAtMs ?? Date.now() }));
    void outbox.drain();
    void refreshDashboard();
    return;
  }
  const total = threadTotalTokens(event) ?? 0;
  if (!total) return;
  const observationKey = `${issueNumber}:${event?.params?.threadId ?? "unknown"}`;
  const previous = observedUsage.get(observationKey) ?? 0;
  const tokens = Math.max(0, total - previous);
  observedUsage.set(observationKey, Math.max(previous, total));
  if (!tokens) return;
  await store.update((state) => {
    const jobKey = String(issueNumber).split(":", 1)[0];
    recordUsage(state, { day: utcDay(), jobKey, tokens, dailyTokenLimit: config.dailyTokenLimit, estimatedUsdPerMillionTokens: config.estimatedUsdPerMillionTokens, emitMilestones: false });
    if (state.jobs[jobKey]) enqueueJobDestinations(state, jobKey);
  });
  void outbox.drain();
  void refreshDashboard();
}

async function runIssue(issue) {
  const key = String(issue.number);
  try {
    await updateJob(key, (state) => { state.jobs[key] = { ...(state.jobs[key] ?? {}), issue: issue.number, issueNodeId: issue.node_id, title: issue.title, status: "preparing", attempts: (state.jobs[key]?.attempts ?? 0) + 1, error: null, nextRetryAt: null }; });
    const { workspace, branch } = await workspaces.prepare(issue);
    await updateJob(key, (state) => { state.jobs[key].status = "running"; state.jobs[key].workspace = workspace; state.jobs[key].branch = branch; });
    const workflow = await fs.readFile(path.join(config.sourceRepository, "WORKFLOW.md"), "utf8").catch(() => "");
    const issuePrompt = `Issue #${issue.number}: ${issue.title}\n\n${issue.body ?? ""}`;
    const runRole = async ({ role, meter, prompt, sandboxMode, instructions }) => {
      const codex = new CodexAppServer(config, {
        workspace,
        onEvent: (event) => { log("codex_event", { issue: issue.number, role, type: event.event }); return accountUsage(`${issue.number}:${meter}`, event); },
        onAdmission: () => budgetStatus(config, store.state.usage[utcDay()] ?? {}, store.state.accountUsage).allowed,
        onApproval: async (method) => {
          await queueDiscordNotice(
            `permission-blocked:${issue.number}:${store.state.jobs[key]?.attempts ?? 0}:${meter}:${String(method).slice(0, 80)}`,
            `⛔ #${issue.number} ${role}가 차단된 권한을 요청했습니다: \`${method}\`. 요청은 자동 거부됩니다.`,
          );
          return { decision: "decline" };
        },
      });
      const turn = await codex.start(prompt, { sandboxMode, networkAccess: sandboxMode === "workspace-write", developerInstructions: instructions });
      return redact(agentText(turn) || "결과 메시지가 없습니다.");
    };

    await updateJob(key, (state) => { state.jobs[key].status = "planning"; });
    const plan = await runRole({
      role: "아키(기획·설계)",
      meter: "architect",
      sandboxMode: "read-only",
      instructions: "You are 아키, the senior product planner and systems architect. Analyze only; never edit files, push, deploy, or request credentials. Produce one concise product and technical contract for 픽시, with explicit review criteria for 센티넬.",
      prompt: `${workflow}\n\n# 역할: 아키(기획·설계)\n${issuePrompt}\n\n저장소와 요구사항을 읽고 통합 기획·설계 계약을 작성하세요. 사용자 문제와 여정, 우선순위, 범위와 비범위, 엣지 케이스, 영향 파일, 데이터·상태 흐름, 인터페이스, 설계 결정, 테스트, 보안·성능 위험, 측정 가능한 완료 기준을 포함하세요. 픽시가 디자인과 구현을 바로 진행하고 센티넬이 독립 검증할 수 있을 정도로 구체적이어야 합니다.`,
    });
    await store.update((state) => { state.jobs[key].plan = plan; });
    await queueVisualOutcome(key, {
      stage: "PLAN_LOCKED", role: "architect", revision: `attempt-${store.state.jobs[key]?.attempts ?? 0}`,
      headline: "기획·안전 계약 확정",
      changes: ["작업 범위와 비범위 확정", "안전·개인정보 경계 명시", "센티넬 수용 기준 정의"],
      tests: [{ state: "NOT_RUN", name: "구현 검증", detail: "픽시 구현 전" }],
      nextStep: "픽시가 디자인과 구현을 진행합니다.",
    });
    await updateJob(key, (state) => { state.jobs[key].status = "implementing"; });
    let implementationReport = await runRole({
      role: "픽시(디자인·개발)",
      meter: "designer-builder-initial",
      sandboxMode: "workspace-write",
      instructions: "You are 픽시, the senior female product designer and implementation engineer. Follow 아키's product and technical contract and repository instructions. Define the UX before editing, then implement and test inside the workspace. Commit locally, and never push, merge, deploy, or access credentials. Report structured test evidence in your final response. Never capture or attach the desktop, terminal, IDE, logs, credentials, real users, precise maps, or QR codes.",
      prompt: `${workflow}\n\n# 역할: 픽시(디자인·개발)\n${issuePrompt}\n\n# 아키의 기획·설계 계약\n${plan}\n\n먼저 기존 디자인 시스템을 기준으로 사용자 흐름, 화면 계층, 컴포넌트 상태, 로딩·빈 화면·오류, 반응형, 접근성, 모션, 문구와 시각 완성도 기준을 정한 뒤 곧바로 구현하세요. UI가 없는 작업이면 사용자 경험과 운영 가시성을 설계하고 구현하세요. 관련 테스트를 실행하고 변경 파일, 테스트 증거, 디자인 반영 증거, 남은 위험을 보고한 뒤 로컬 커밋을 만드세요.`,
    });
    await workspaces.ensureCommit(workspace, issue);
    await queueVisualOutcome(key, {
      stage: "IMPLEMENTATION_EVIDENCE", role: "designer", revision: `attempt-${store.state.jobs[key]?.attempts ?? 0}`,
      headline: "디자인·구현 결과 생성",
      changes: ["아키 계약에 따른 기능 구현", "사용자 상태와 안전 경계 반영", "로컬 검사 증거 생성"],
      tests: [{ state: "NOT_RUN", name: "센티넬 독립 검토", detail: "검토 시작 전" }],
      nextStep: "센티넬이 실제 diff와 테스트 증거를 독립 검토합니다.",
      evidenceKind: "UNVERIFIED_PREVIEW",
    });
    let approved = false;
    let finalReview = "";
    for (let round = 1; round <= config.maxReviewRounds; round += 1) {
      await updateJob(key, (state) => { state.jobs[key].status = "reviewing"; state.jobs[key].reviewRound = round; });
      finalReview = await runRole({
        role: "센티넬(검토자)",
        meter: `reviewer-${round}`,
        sandboxMode: "read-only",
        instructions: "You are 센티넬, an independent quality and security reviewer. Never edit files. Inspect the committed diff and test evidence, challenge assumptions, and fail closed. Your sandbox is intentionally read-only: do not reject solely because it prevents a fresh test rerun, but do reject missing or invalid evidence and real product defects. End with exactly VERDICT: APPROVE or VERDICT: CHANGES_REQUIRED.",
        prompt: `# 역할: 센티넬(검토자)\n${issuePrompt}\n\n# 아키의 기획·설계 계약\n${plan}\n\n# 픽시의 디자인·개발 보고\n${implementationReport}\n\n실제 git diff와 테스트를 독립적으로 확인하세요. 기능 정확성, 기획 수용 기준, 디자인 완성도, 모든 UI 상태, 반응형·접근성, 회귀, 보안, 개인정보, 자동차 UX, 테스트 증거를 검토하고 구체적인 발견사항을 작성하세요. 마지막 줄은 반드시 VERDICT: APPROVE 또는 VERDICT: CHANGES_REQUIRED 중 하나여야 합니다.`,
      });
      await store.update((state) => { state.jobs[key].reviews ??= []; state.jobs[key].reviews.push({ round, report: finalReview }); });
      approved = /VERDICT:\s*APPROVE\s*$/im.test(finalReview) && !/VERDICT:\s*CHANGES_REQUIRED/im.test(finalReview);
      if (approved) break;
      await queueVisualOutcome(key, {
        stage: "REVIEW_CHANGES_REQUIRED", role: "reviewer", revision: `attempt-${store.state.jobs[key]?.attempts ?? 0}-review-${round}`,
        headline: `독립 검토 ${round}차 · 수정 필요`,
        changes: ["센티넬이 차단 발견사항을 확인", "현재 결과는 승인되지 않음"],
        tests: [{ state: "REVIEW", name: "센티넬 품질 게이트", detail: "수정 요청" }],
        nextStep: "픽시가 발견사항을 반영한 뒤 다시 검토합니다.",
      });
      if (round === config.maxReviewRounds) throw new Error(`센티넬 검토를 ${config.maxReviewRounds}회 통과하지 못했습니다.`);

      await updateJob(key, (state) => { state.jobs[key].status = "reworking"; });
      implementationReport = await runRole({
        role: "픽시(디자인·개발)",
        meter: `designer-builder-rework-${round}`,
        sandboxMode: "workspace-write",
        instructions: "You are 픽시, the product designer and implementation engineer. Address every blocking functional and design finding from 센티넬, rerun tests, and commit locally. Never push, merge, deploy, or access credentials.",
        prompt: `# 역할: 픽시(디자인·개발) · 재작업\n${issuePrompt}\n\n# 아키의 기획·설계 계약\n${plan}\n\n# 센티넬의 반려 의견\n${finalReview}\n\n각 지적을 수정하고 테스트를 다시 실행하세요. 무엇을 어떻게 해결했는지 기능·디자인 근거와 함께 보고하고 로컬 커밋을 만드세요.`,
      });
      await workspaces.ensureCommit(workspace, issue);
    }

    await updateJob(key, (state) => { state.jobs[key].status = "publishing"; });
    const commit = await workspaces.ensureCommit(workspace, issue);
    await workspaces.push(workspace, branch);
    const pr = await github.createPullRequest(branch, `[Agent] ${issue.title}`, `Closes #${issue.number}\n\nThree-role autonomous workflow completed: 아키 product planning and architecture, 픽시 product design and implementation, 센티넬 independent approval. Merge is gated by Discord approval.\n\nCommit: ${commit}\n\nReviewer verdict:\n${finalReview.slice(-1200)}`);
    const id = approvalId(pr);
    await updateJob(key, (state) => {
      state.jobs[key] = { ...state.jobs[key], status: "awaiting_merge_approval", pr: pr.number, prUrl: pr.html_url, commit, updatedAt: new Date().toISOString() };
      state.approvals[id] = bindApprovalFollowUp(
        { id, type: "merge", issue: issue.number, pr: pr.number, commit, createdAt: Date.now(), expiresAt: Date.now() + config.approvalTtlMs, status: "pending" },
        `${config.githubOwner}/${config.githubRepo}`,
      );
    });
    await queueVisualOutcome(key, {
      stage: "PR_READY", role: "team", revision: commit,
      headline: "3역할 품질 게이트 통과 · PR 준비",
      changes: ["아키 설계 계약 반영", "픽시 디자인·구현 완료", "센티넬 독립 검토 승인"],
      tests: [
        { state: "PASS", name: "센티넬 독립 검토", detail: "APPROVE" },
        { state: "NOT_RUN", name: "PR 병합", detail: "사용자 승인 대기" },
        { state: "NOT_RUN", name: "AWS 배포", detail: "별도 승인 전 미실행" },
      ],
      nextStep: "변경사항을 확인한 뒤 별도의 승인 카드에서 병합 여부를 결정해 주세요.",
      links: [pr.html_url],
    });
    await github.removeReadyLabel(issue.number).catch(() => {});
    await github.comment(issue.number, `Codex completed the implementation: ${pr.html_url}\n\nMerge is waiting for Discord approval.`);
    await discord.requestApproval({ id, title: `PR #${pr.number} 병합`, description: `#${issue.number} ${issue.title}\n\nCodex 구현과 검증이 끝났습니다. PR을 검토한 뒤 버튼을 선택하세요.`, url: pr.html_url });
  } catch (error) {
    log("job_failed", { issue: issue.number, error: redact(error.message) });
    const usageLimited = isUsageLimitError(error);
    const reviewChangesRequired = isReviewChangesRequiredError(error);
    await updateJob(key, (state) => {
      const job = state.jobs[key] ?? { issue: issue.number, attempts: 1 };
      job.status = classifyJobFailure({
        usageLimited,
        reviewChangesRequired,
        attempts: job.attempts,
        maxRetries: config.maxRetries,
      });
      job.error = redact(error.message);
      job.nextRetryAt = usageLimited ? Date.now() + config.usageLimitCooldownMs : null;
      job.updatedAt = new Date().toISOString(); state.jobs[key] = job;
    });
    const failedStatus = store.state.jobs[key]?.status;
    const failureEvent = `job-failure:${issue.number}:${store.state.jobs[key]?.attempts ?? 1}:${failedStatus}`;
    await queueDiscordNotice(failureEvent, failureNotice({
      issue: issue.number,
      status: failedStatus,
      attempts: store.state.jobs[key]?.attempts ?? 1,
      maxRetries: config.maxRetries,
      usageLimitCooldownMs: config.usageLimitCooldownMs,
    }));
  } finally { active.delete(key); }
}

async function poll() {
  await recoverMergeApprovals();
  await expireApprovals();
  if (Date.now() - lastAccountUsageSyncAt >= config.accountUsageSyncMs) {
    try {
      const snapshot = await readCodexAccountState(config);
      await store.update((state) => {
        if (snapshot.accountUsage) recordAccountTokenSnapshot(state, snapshot.accountUsage, { day: utcDay() });
        if (snapshot.accountRateLimits) recordAccountRateLimit(state, snapshot.accountRateLimits);
      });
      lastAccountUsageSyncAt = Date.now();
    } catch (error) {
      log("account_usage_sync_failed", { error: redact(error.message) });
    }
  }
  const issues = await github.listReadyIssues();
  const readyToStart = await reconcileReadyIssues({
    issues,
    store,
    activeKeys: new Set(active.keys()),
    maxParallel: config.maxParallel,
    enqueueSnapshot: enqueueJobDestinations,
  });
  void outbox.drain();
  void refreshDashboard();
  const usage = store.state.usage[utcDay()] ?? { tokens: 0, estimatedUsd: 0 };
  const budget = budgetStatus(config, usage, store.state.accountUsage);
  if (!budget.allowed) { log("budget_paused", { reason: budget.reason, usage }); return; }
  for (const issue of readyToStart) {
    const key = String(issue.number);
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
      const markExpired = (state) => {
        state.approvals[approval.id].status = "expired";
        if (approval.issue && state.jobs[String(approval.issue)]) {
          state.jobs[String(approval.issue)].status = approval.type === "aws-deploy" ? "deploy_approval_expired" : "merge_approval_expired";
        }
      };
      if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), markExpired);
      else await store.update(markExpired);
      await discord.report(`⌛ 승인 만료: \`${approval.id}\`${approval.type === "aws-deploy" ? " — 미실행 변경 세트를 삭제했습니다." : ""}`);
    } catch (error) {
      const failExpiryCleanup = (state) => {
        state.approvals[approval.id].status = "failed"; state.approvals[approval.id].error = redact(error.message);
        if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = approval.type === "aws-deploy" ? "deploy_failed" : "merge_failed";
      };
      if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), failExpiryCleanup);
      else await store.update(failExpiryCleanup);
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
        const restorePending = (state) => {
          state.approvals[approval.id].status = "pending"; delete state.approvals[approval.id].processingBy;
          if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "awaiting_deploy_approval";
        };
        if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), restorePending);
        else await store.update(restorePending);
        await discord.requestApproval({
          id: approval.id,
          title: `복구된 AWS dev 배포 · ${approval.changeSetType}`,
          description: `재시작 전에 실행되지 않은 변경 세트입니다. 다시 결정해 주세요.\n\n${formatChangeSummary(approval.changes ?? [])}`,
        });
      } else {
        const finishRecovery = (state) => {
          state.approvals[approval.id].status = "approved";
          if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "deployed";
        };
        if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), finishRecovery);
        else await store.update(finishRecovery);
        await discord.report(`✅ **재시작 후 AWS 배포 복구 완료**\n승인 ID: \`${approval.id}\`\n상태: \`${recovery.result.stackStatus}\``);
      }
    } catch (error) {
      const failRecovery = (state) => {
        state.approvals[approval.id].status = "failed"; state.approvals[approval.id].error = redact(error.message);
        if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "deploy_failed";
      };
      if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), failRecovery);
      else await store.update(failRecovery);
      await discord.report(`❌ **중단된 AWS 배포 복구 실패**\n승인 ID: \`${approval.id}\`\n${redact(error.message).slice(0, 1200)}`);
    } finally { deploymentActive = false; }
  }
}

async function recoverMergeApprovals() {
  if (mergeRecoveryActive) return;
  mergeRecoveryActive = true;
  try {
    const interrupted = Object.values(store.state.approvals)
      .filter((item) => item.type === "merge" && item.status === "processing");
    for (const approval of interrupted) {
      try {
        const pull = await github.getPullRequest(approval.pr);
        const decision = mergeRecoveryDecision({ approval, pull });
        if (decision === "merged") {
          await updateJob(String(approval.issue), (state) => recordMergeResult(state, {
            approvalId: approval.id,
            approval,
            result: { merged: true, sha: pull.merge_commit_sha },
            userId: approval.processingBy ?? "recovered",
            repository: `${config.githubOwner}/${config.githubRepo}`,
          }));
          await queueDiscordNotice(
            `merge-recovered:${config.githubOwner}/${config.githubRepo}:pr-${approval.pr}`,
            `✅ **재시작 후 병합 상태 복구 완료**\nPR #${approval.pr}의 완료 상태와 후속 작업 큐를 복구했습니다.`,
          );
        } else if (decision === "retry-approval") {
          await updateJob(String(approval.issue), (state) => {
            state.approvals[approval.id].status = "pending";
            delete state.approvals[approval.id].processingBy;
            delete state.approvals[approval.id].processingStartedAt;
            state.approvals[approval.id].expiresAt = Date.now() + config.approvalTtlMs;
            state.jobs[String(approval.issue)].status = "awaiting_merge_approval";
          });
          await discord.requestApproval({
            id: approval.id,
            title: `복구된 PR #${approval.pr} 병합 승인`,
            description: "재시작 전에 병합이 실행되지 않았습니다. 변경사항을 확인한 뒤 다시 결정해 주세요.",
            url: pull.html_url,
          });
        }
      } catch (error) {
        log("merge_recovery_failed", { pr: approval.pr, error: redact(error.message) });
      }
    }
  } finally {
    mergeRecoveryActive = false;
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
    const deploymentJobKey = issue ? String(issue) : null;
    const persistDeploymentApproval = (state) => {
      state.approvals[id] = { ...prepared, createdAt: Date.now(), expiresAt: Date.now() + config.approvalTtlMs, status: "pending" };
      if (issue && state.jobs[String(issue)]) state.jobs[String(issue)].status = "awaiting_deploy_approval";
    };
    if (deploymentJobKey && store.state.jobs[deploymentJobKey]) await updateJob(deploymentJobKey, persistDeploymentApproval);
    else await store.update(persistDeploymentApproval);
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
    candidate.processingStartedAt = Date.now();
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
        const rejectDeployment = (state) => {
          state.approvals[id].status = "rejected"; state.approvals[id].decidedBy = userId;
          if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "deploy_rejected";
        };
        if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), rejectDeployment);
        else await store.update(rejectDeployment);
        await discord.report(`🛑 **AWS 배포 거부**\n변경 세트 \`${id}\`를 삭제했습니다. AWS 리소스는 변경되지 않았습니다.\n결정자: <@${userId}>`);
        return;
      }
      await discord.report(`🚀 **AWS dev 배포 승인됨**\n승인자: <@${userId}>\n변경 세트 \`${id}\` 실행을 시작합니다.`);
      const result = await aws.execute(approval, (message) => discord.report(`☁️ ${message}`));
      const deploymentJobKey = approval.issue ? String(approval.issue) : null;
      const markDeployed = (state) => {
        state.approvals[id].status = "approved"; state.approvals[id].decidedBy = userId;
        if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "deployed";
      };
      if (deploymentJobKey && store.state.jobs[deploymentJobKey]) await updateJob(deploymentJobKey, markDeployed);
      else await store.update(markDeployed);
      const smoke = result.smoke.attempted ? `HTTP ${result.smoke.status} · 인증 차단 정상` : "API URL 출력 없음";
      await discord.report(`✅ **AWS dev 배포 완료**\n상태: \`${result.stackStatus}\`\n보안 스모크 테스트: ${smoke}\n출력 항목: ${result.outputKeys.map((key) => `\`${key}\``).join(", ") || "없음"}`);
    } catch (error) {
      const failDeployment = (state) => {
        state.approvals[id].status = "failed"; state.approvals[id].error = redact(error.message);
        if (approval.issue && state.jobs[String(approval.issue)]) state.jobs[String(approval.issue)].status = "deploy_failed";
      };
      if (approval.issue && store.state.jobs[String(approval.issue)]) await updateJob(String(approval.issue), failDeployment);
      else await store.update(failDeployment);
      await discord.report(`❌ **AWS 배포 처리 실패**\n승인 ID: \`${id}\`\n${redact(error.message).slice(0, 1500)}`);
    } finally {
      deploymentActive = false;
    }
    return;
  }
  if (action === "approve") {
    let result;
    try {
      result = await github.mergePullRequest(approval.pr, approval.commit);
    } catch (error) {
      if (error?.status === 405) {
        await updateJob(String(approval.issue), (state) => {
          state.approvals[id].status = "failed";
          state.approvals[id].decidedBy = userId;
          state.approvals[id].error = "GitHub rejected the merge because the pull request is not currently mergeable";
          state.jobs[String(approval.issue)].status = "merge_failed";
          state.jobs[String(approval.issue)].error = "Pull request requires conflict resolution or a fresh base before merge";
          state.jobs[String(approval.issue)].updatedAt = new Date().toISOString();
        });
        await queueDiscordNotice(
          `merge-conflict:${config.githubOwner}/${config.githubRepo}:pr-${approval.pr}`,
          `🧩 **PR #${approval.pr} 병합 충돌**\n승인은 정상 접수됐지만 GitHub가 병합을 거절했습니다. 충돌 해결과 재검증 후 새 커밋 기준 승인 카드를 발행합니다.`,
        );
        return;
      }
      log("merge_result_ambiguous", { pr: approval.pr, error: redact(error.message) });
      await queueDiscordNotice(
        `merge-reconcile-pending:${config.githubOwner}/${config.githubRepo}:pr-${approval.pr}`,
        `⏳ PR #${approval.pr} 병합 응답이 불명확해 원격 상태를 재확인 중입니다. 중복 병합 요청 없이 최대 2분 동안 자동 조정합니다.`,
      );
      return;
    }

    try {
      await updateJob(String(approval.issue), (state) => recordMergeResult(state, {
        approvalId: id,
        approval,
        result,
        userId,
        repository: `${config.githubOwner}/${config.githubRepo}`,
      }));
    } catch (error) {
      log("merge_state_persist_failed", { pr: approval.pr, error: redact(error.message) });
      await discord.report(`⚠️ PR #${approval.pr}의 원격 병합 결과를 로컬 상태에 저장하지 못했습니다. 재시작 복구가 원격 상태를 다시 확인합니다.`)
        .catch((reportError) => log("merge_state_report_failed", { pr: approval.pr, error: redact(reportError.message) }));
      return;
    }

    await queueDiscordNotice(
      `merge-result:${config.githubOwner}/${config.githubRepo}:pr-${approval.pr}:${result.merged ? "merged" : "failed"}`,
      result.merged ? `🚀 PR #${approval.pr} 병합 완료 — 승인자 <@${userId}>` : `❌ PR #${approval.pr} 병합 실패: ${redact(result.message)}`,
    );
    if (result.merged) {
      try {
        try {
          const files = await github.listPullRequestFiles(approval.pr);
          if (files.some((file) => file.filename === "infra" || file.filename.startsWith("infra/"))) {
            void prepareAwsDeployment({ sourceSha: result.sha, pr: approval.pr, issue: approval.issue, requestedBy: userId })
              .catch((error) => log("automatic_deploy_prepare_failed", { error: redact(error.message) }));
          }
        } catch (error) {
          log("post_merge_file_inspection_failed", { pr: approval.pr, error: redact(error.message) });
          await discord.report(`⚠️ PR #${approval.pr} 병합은 완료됐지만 후속 배포 대상 파일 확인에 실패했습니다. 병합 상태는 유지하며 AWS 배포는 시작하지 않습니다.`)
            .catch((reportError) => log("post_merge_file_report_failed", { pr: approval.pr, error: redact(reportError.message) }));
        }
      } catch (error) {
        log("post_merge_processing_failed", { pr: approval.pr, error: redact(error.message) });
      }
    }
  } else {
    await updateJob(String(approval.issue), (state) => { state.approvals[id].status = "rejected"; state.approvals[id].decidedBy = userId; state.jobs[String(approval.issue)].status = "rejected"; });
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
  const usage = store.state.usage[utcDay()] ?? { tokens: 0, estimatedUsd: 0 };
  await discord.reply(interaction, renderAgentStatus({
    jobs: Object.values(store.state.jobs),
    approvals: Object.values(store.state.approvals),
    usage,
    accountUsage: store.state.accountUsage,
    config,
    activeCount: active.size,
    deploymentActive,
  }));
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
await store.update((state) => {
  migrateApprovalFollowUps(state, `${config.githubOwner}/${config.githubRepo}`);
  for (const key of Object.keys(state.jobs)) enqueueJobDestinations(state, key);
});
void outbox.drain();
await refreshDashboard();
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
  log("discord_ready", { repository: `${config.githubOwner}/${config.githubRepo}`, maxParallel: config.maxParallel });
  void recoverMergeApprovals().catch((error) => log("merge_recovery_scan_failed", { error: redact(error.message) }));
  if (config.awsDeployEnabled) void recoverAwsApprovals().catch((error) => log("aws_recovery_failed", { error: redact(error.message) }));
});
await poll().catch((error) => log("poll_failed", { error: error.message }));
setInterval(() => poll().catch((error) => log("poll_failed", { error: error.message })), config.pollIntervalMs);
setInterval(() => void outbox.drain(), config.outboxIntervalMs);
setInterval(() => void refreshDashboard(), config.dashboardIntervalMs);

