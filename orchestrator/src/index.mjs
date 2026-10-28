import fs from "node:fs/promises";
import path from "node:path";
import { loadConfig } from "./config.mjs";
import { Store, utcDay } from "./store.mjs";
import { budgetStatus } from "./policy.mjs";
import { GitHub } from "./github.mjs";
import { Discord } from "./discord.mjs";
import { WorkspaceManager } from "./workspace.mjs";
import { CodexAppServer } from "./codex.mjs";

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
    await store.update((state) => { state.jobs[key] = { ...(state.jobs[key] ?? {}), issue: issue.number, title: issue.title, status: "preparing", attempts: (state.jobs[key]?.attempts ?? 0) + 1, updatedAt: new Date().toISOString() }; });
    const { workspace, branch } = await workspaces.prepare(issue);
    await store.update((state) => { state.jobs[key].status = "running"; state.jobs[key].workspace = workspace; state.jobs[key].branch = branch; });
    await discord.report(`🤖 **#${issue.number} ${issue.title}** 작업을 시작했습니다. 브랜치: \`${branch}\``);

    const workflow = await fs.readFile(path.join(config.sourceRepository, "WORKFLOW.md"), "utf8").catch(() => "");
    const prompt = `${workflow}\n\n# Assigned GitHub Issue\n\nIssue #${issue.number}: ${issue.title}\n\n${issue.body ?? ""}\n\nImplement only this issue. Run tests and commit locally; do not push.`;
    const codex = new CodexAppServer(config, {
      workspace,
      onEvent: (event) => { log("codex_event", { issue: issue.number, type: event.event }); void accountUsage(issue.number, event); },
      onApproval: async (method, params) => {
        await discord.report(`⛔ #${issue.number} Codex가 차단된 권한을 요청했습니다: \`${method}\`. 요청은 자동 거부됩니다.`);
        return method.includes("fileChange") ? { decision: "decline" } : { decision: "decline" };
      },
    });
    await codex.start(prompt);
    await store.update((state) => { state.jobs[key].status = "publishing"; });
    const commit = await workspaces.ensureCommit(workspace, issue);
    await workspaces.push(workspace, branch);
    const pr = await github.createPullRequest(branch, `[Agent] ${issue.title}`, `Closes #${issue.number}\n\nAutonomous Codex implementation. Merge is gated by Discord approval.\n\nCommit: ${commit}`);
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
    await store.update((state) => {
      const job = state.jobs[key] ?? { issue: issue.number, attempts: 1 };
      job.status = job.attempts <= config.maxRetries ? "retry" : "failed"; job.error = error.message; job.updatedAt = new Date().toISOString(); state.jobs[key] = job;
    });
    await discord.report(`❌ **#${issue.number} 작업 실패**\n${redact(error.message).slice(0, 1500)}`);
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

