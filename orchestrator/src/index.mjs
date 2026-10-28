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
    await discord.report(`✅ **#${issue.number} 구현 완료**\n${pr.html_url}\n병합 승인: \`!approve ${id}\`\n거부: \`!reject ${id}\``);
  } catch (error) {
    log("job_failed", { issue: issue.number, error: error.message });
    await store.update((state) => {
      const job = state.jobs[key] ?? { issue: issue.number, attempts: 1 };
      job.status = job.attempts <= config.maxRetries ? "retry" : "failed"; job.error = error.message; job.updatedAt = new Date().toISOString(); state.jobs[key] = job;
    });
    await discord.report(`❌ **#${issue.number} 작업 실패**\n${String(error.message).slice(0, 1500)}`);
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

discord.on("approval", async ({ action, id, userId }) => {
  const approval = store.state.approvals[id];
  if (!approval || approval.status !== "pending") return void discord.report(`⚠️ 유효한 대기 승인이 아닙니다: \`${id}\``);
  if (Date.now() > approval.expiresAt) action = "reject";
  if (action === "approve") {
    const result = await github.mergePullRequest(approval.pr);
    await store.update((state) => { state.approvals[id].status = "approved"; state.approvals[id].decidedBy = userId; state.jobs[String(approval.issue)].status = result.merged ? "merged" : "merge_failed"; });
    await discord.report(result.merged ? `🚀 PR #${approval.pr} 병합 완료 — 승인자 <@${userId}>` : `❌ PR #${approval.pr} 병합 실패: ${result.message}`);
  } else {
    await store.update((state) => { state.approvals[id].status = "rejected"; state.approvals[id].decidedBy = userId; state.jobs[String(approval.issue)].status = "rejected"; });
    await discord.report(`🛑 PR #${approval.pr} 병합 거부 — <@${userId}>`);
  }
});

await store.load();
discord.connect();
await discord.report(`🟢 Social Drive 오케스트레이터 시작 — 최대 병렬 Agent ${config.maxParallel}개`);
await poll().catch((error) => log("poll_failed", { error: error.message }));
setInterval(() => poll().catch((error) => log("poll_failed", { error: error.message })), config.pollIntervalMs);

