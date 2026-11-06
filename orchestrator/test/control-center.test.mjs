import test from "node:test";
import assert from "node:assert/strict";
import {
  DISCORD_STAGE_STATUSES,
  STATUS_CONTRACT,
  buildProgressSnapshot,
  claimTokenMilestones,
  discordStageEventKey,
  mappingFor,
  milestoneMessage,
  renderAgentStatus,
  renderDiscordStageReport,
  renderProgressComment,
} from "../src/control-center.mjs";

const expected = {
  ready: ["Ready", "Queue", "On track", "Orchestrator"],
  removed_from_queue: ["Cancelled", "Queue", "On track", "Orchestrator"],
  preparing: ["In progress", "Queue", "On track", "Orchestrator"],
  running: ["In progress", "Queue", "On track", "Orchestrator"],
  planning: ["In progress", "Planning", "On track", "아키"],
  implementing: ["In progress", "Build", "On track", "픽시"],
  reviewing: ["Review", "Review", "On track", "센티넬"],
  reworking: ["Review", "Build", "At risk", "픽시"],
  publishing: ["Review", "PR", "On track", "Orchestrator"],
  handoff_deferred: ["Review", "PR", "On track", "Orchestrator"],
  awaiting_merge_approval: ["Approval needed", "Merge", "At risk", "Human"],
  awaiting_deploy_approval: ["Approval needed", "Deploy", "At risk", "Human"],
  merge_approval_expired: ["Blocked", "Merge", "Blocked", "Human"],
  deploy_approval_expired: ["Blocked", "Deploy", "Blocked", "Human"],
  retry: ["Blocked", "Queue", "Recovering", "Orchestrator"],
  review_retry: ["Review", "Review", "At risk", "픽시"],
  paused_usage_limit: ["Blocked", "Queue", "Blocked", "Orchestrator"],
  merge_failed: ["Blocked", "Merge", "Blocked", "Orchestrator"],
  deploy_failed: ["Blocked", "Deploy", "Blocked", "Orchestrator"],
  failed: ["Blocked", "Queue", "Blocked", "Orchestrator"],
  review_blocked: ["Blocked", "Review", "At risk", "센티넬"],
  rejected: ["Blocked", "Merge", "Blocked", "Human"],
  deploy_rejected: ["Blocked", "Deploy", "Blocked", "Human"],
  merged: ["Done", "Merge", "On track", "Orchestrator"],
  deployed: ["Done", "Deploy", "On track", "Orchestrator"],
};

test("maps every internal status to one stable project contract", () => {
  assert.deepEqual(Object.keys(STATUS_CONTRACT).sort(), Object.keys(expected).sort());
  for (const [status, values] of Object.entries(expected)) {
    const mapped = mappingFor(status);
    assert.deepEqual([mapped.status, mapped.phase, mapped.health, mapped.persona], values, status);
    assert.ok(mapped.progress >= 0 && mapped.progress <= 100, status);
  }
});

test("renders a safe progress snapshot without raw errors or workspace paths", () => {
  const snapshot = buildProgressSnapshot({
    issue: 42,
    title: "관제판\n추가",
    status: "reviewing",
    attempts: 1,
    reviewRound: 2,
    tokens: 12345,
    workspace: "/Users/private/secret",
    error: "token=must-not-leak",
    branch: "agent/issue-42",
    updatedAt: "2026-11-01T10:00:00.000Z",
  }, { dailyUsage: { tokens: 500000 }, dailyTokenLimit: 2000000 });
  const rendered = renderProgressComment(snapshot);
  assert.match(rendered, /<!-- social-drive-control-center:issue-42 -->/);
  assert.match(rendered, /센티넬 독립 검토 중 · 2차/);
  assert.match(rendered, /25%/);
  assert.doesNotMatch(rendered, /\/Users\/private|must-not-leak/);
});

test("keeps routine stage transitions out of Discord while preserving safe rendering", () => {
  assert.equal(DISCORD_STAGE_STATUSES.has("planning"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("implementing"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("reviewing"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("preparing"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("running"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("retry"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("paused_usage_limit"), false);
  assert.equal(DISCORD_STAGE_STATUSES.has("failed"), false);
  const snapshot = buildProgressSnapshot({
    issue: 42,
    title: "개인정보 안전 게이트",
    status: "reviewing",
    attempts: 1,
    reviewRound: 2,
    tokens: 100,
    workspace: "/Users/private/secret",
    error: "token=must-not-leak",
    updatedAt: "2026-11-01T10:00:00.000Z",
  }, { dailyUsage: { tokens: 100 }, dailyTokenLimit: 1000 });
  const rendered = renderDiscordStageReport(snapshot);
  assert.match(rendered, /#42 Review · 센티넬/);
  assert.match(rendered, /검토 2차/);
  assert.match(rendered, /진행 70%/);
  assert.match(rendered, /다음:/);
  assert.doesNotMatch(rendered, /\/Users\/private|must-not-leak/);
  assert.ok(rendered.length <= 1800);
  assert.equal(discordStageEventKey("42", { status: "reviewing", attempts: 1, reviewRound: 1 }), "stage:42:reviewing:1:1");
  assert.notEqual(
    discordStageEventKey("42", { status: "reviewing", attempts: 1, reviewRound: 1 }),
    discordStageEventKey("42", { status: "reviewing", attempts: 2, reviewRound: 1 }),
  );
});

test("claims 30/60/90 once and groups a threshold jump into one message", () => {
  const first = claimTokenMilestones({ tokens: 650, limit: 1000, emitted: [] });
  assert.deepEqual(first.claimed, [30, 60]);
  assert.deepEqual(first.emitted, [30, 60]);
  const message = milestoneMessage({ claimed: first.claimed, tokens: 650, limit: 1000, estimatedUsd: 1.25 });
  assert.match(message, /30% · 60%/);
  assert.equal((message.match(/도달/g) ?? []).length, 1);
  const second = claimTokenMilestones({ tokens: 950, limit: 1000, emitted: first.emitted });
  assert.deepEqual(second.claimed, [90]);
  const duplicate = claimTokenMilestones({ tokens: 999, limit: 1000, emitted: second.emitted });
  assert.deepEqual(duplicate.claimed, []);
});

test("renders approval and blocked jobs first in a bounded status response", () => {
  const jobs = [
    { issue: 1, title: "정상 구현", status: "implementing", updatedAt: "2026-11-01T10:00:00.000Z" },
    { issue: 2, title: "승인 필요", status: "awaiting_merge_approval", pr: 9, updatedAt: "2026-11-01T10:01:00.000Z" },
    { issue: 3, title: "사용량 정지", status: "paused_usage_limit", updatedAt: "2026-11-01T10:02:00.000Z" },
    ...Array.from({ length: 5 }, (_, index) => ({ issue: 10 + index, title: `대기 ${index}`, status: "ready", updatedAt: "2026-11-01T10:03:00.000Z" })),
  ];
  const response = renderAgentStatus({
    jobs,
    approvals: [{ issue: 2, type: "merge", status: "pending", expiresAt: Date.parse("2026-11-01T12:00:00.000Z") }],
    usage: { tokens: 620000, estimatedUsd: 6.2 },
    config: { maxParallel: 3, dailyTokenLimit: 2000000, dailyCostLimitUsd: 25, githubProjectUrl: "https://github.com/orgs/demo/projects/1" },
    activeCount: 1,
    now: new Date("2026-11-01T11:00:00.000Z"),
  });
  assert.ok(response.indexOf("#2") < response.indexOf("#3"));
  assert.ok(response.indexOf("#3") < response.indexOf("#1"));
  assert.match(response, /외 3개/);
  assert.match(response, /오늘 토큰 620,000 \/ 2,000,000 \(31%\)/);
  assert.ok(response.length <= 1900);
});

test("renders actual account usage without a temporal-dead-zone failure", () => {
  const rendered = renderAgentStatus({
    jobs: [], approvals: [], usage: {},
    accountUsage: { usedPercent: 52, remainingPercent: 48, todayTokens: 138499664, lifetimeTokens: 1624957643, resetsAt: 1793774166 },
    config: { maxParallel: 3, dailyTokenLimit: 2000000, dailyCostLimitUsd: 25, githubProjectUrl: "" },
    activeCount: 1,
  });
  assert.match(rendered, /실제 7일 한도 52%/);
  assert.match(rendered, /138,499,664/);
});
