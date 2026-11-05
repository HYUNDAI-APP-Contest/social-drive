import test from "node:test";
import assert from "node:assert/strict";
import {
  activateFollowUp,
  bindApprovalFollowUp,
  enqueueFollowUpAudit,
  mergeRecoveryDecision,
  migrateApprovalFollowUps,
  recordMergeResult,
} from "../src/follow-up.mjs";

const repository = "HYUNDAI-APP-Contest/social-drive";

const baseState = () => ({
  jobs: { "5": { issue: 5, status: "awaiting_merge_approval" } },
  approvals: { "pr-2": { id: "pr-2", type: "merge", issue: 5, pr: 2, status: "processing" } },
  outbox: {},
});

test("trusted PR 2 dependency persists Issue 9 while unrelated approvals remain unbound", () => {
  assert.equal(bindApprovalFollowUp({ pr: 2 }, repository).followUpIssue, 9);
  assert.equal(bindApprovalFollowUp({ pr: 3, followUpIssue: 12345 }, repository).followUpIssue, undefined);
  const state = baseState();
  migrateApprovalFollowUps(state, repository);
  assert.equal(state.approvals["pr-2"].followUpIssue, 9);
});

test("only a successful merged result durably activates the trusted follow-up", () => {
  const state = baseState();
  migrateApprovalFollowUps(state, repository);
  const approval = structuredClone(state.approvals["pr-2"]);
  recordMergeResult(state, {
    approvalId: "pr-2", approval, result: { merged: true }, userId: "owner",
    repository, now: 1000,
  });
  assert.equal(state.approvals["pr-2"].status, "approved");
  assert.equal(state.jobs["5"].status, "merged");
  const entries = Object.values(state.outbox);
  assert.equal(entries.length, 1);
  assert.equal(entries[0].destination, "github-follow-up-activation");
  assert.deepEqual(entries[0].payload, { issue: 9, pr: 2, repository });
});

test("a rejected merge records a failed approval instead of pretending approval completed", () => {
  const state = baseState();
  const approval = structuredClone(state.approvals["pr-2"]);
  recordMergeResult(state, {
    approvalId: "pr-2", approval, result: { merged: false, message: "Pull Request has merge conflicts" }, userId: "owner",
    repository: "HYUNDAI-APP-Contest/social-drive",
  });
  assert.equal(state.approvals["pr-2"].status, "failed");
  assert.equal(state.jobs["5"].status, "merge_failed");
  assert.match(state.approvals["pr-2"].error, /merge conflicts/);
});

test("merge rejection, missing mapping, and invalid targets perform no follow-up mutation", () => {
  for (const { approval, result } of [
    { approval: { id: "pr-2", issue: 5, pr: 2, followUpIssue: 9 }, result: { merged: false } },
    { approval: { id: "pr-2", issue: 5, pr: 3, followUpIssue: 9 }, result: { merged: true } },
    { approval: { id: "pr-2", issue: 5, pr: 77, followUpIssue: 12345 }, result: { merged: true } },
  ]) {
    const state = baseState();
    recordMergeResult(state, { approvalId: "pr-2", approval, result, userId: "owner", repository });
    assert.equal(Object.keys(state.outbox).length, 0);
  }
});

test("activation completion creates stable independent audit and Discord entries", () => {
  const state = { outbox: {} };
  const input = { issue: 9, pr: 2, repository: "o/r", readyLabel: "agent:ready" };
  enqueueFollowUpAudit(state, input, 1000);
  const keys = Object.keys(state.outbox).sort();
  enqueueFollowUpAudit(state, input, 2000);
  assert.deepEqual(Object.keys(state.outbox).sort(), keys);
  assert.deepEqual(Object.values(state.outbox).map((entry) => entry.destination).sort(), ["discord-report", "github-follow-up-audit"]);
});

test("activation rejects stale repository and untrusted targets before any GitHub call", async () => {
  let calls = 0;
  const github = {
    requireOpenIssue: async () => { calls += 1; },
    addReadyLabel: async () => { calls += 1; },
  };
  const store = { update: async () => { calls += 1; } };
  await assert.rejects(() => activateFollowUp({
    github, store, payload: { issue: 9, pr: 2, repository: "old/repo" }, currentRepository: repository, readyLabel: "agent:ready",
  }), /repository/);
  await assert.rejects(() => activateFollowUp({
    github, store, payload: { issue: 10, pr: 2, repository }, currentRepository: repository, readyLabel: "agent:ready",
  }), /trusted/);
  assert.equal(calls, 0);
});

test("ambiguous merge remains recoverable until remote truth settles", () => {
  const approval = { processingStartedAt: 1000 };
  assert.equal(mergeRecoveryDecision({ approval, pull: { merged_at: null }, now: 60_000, ambiguityMs: 120_000 }), "wait");
  assert.equal(mergeRecoveryDecision({ approval, pull: { merged_at: "2026-11-02T00:00:00Z" }, now: 60_000 }), "merged");
  assert.equal(mergeRecoveryDecision({ approval, pull: { merged_at: null }, now: 121_001, ambiguityMs: 120_000 }), "retry-approval");
});
