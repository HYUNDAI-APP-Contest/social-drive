import test from "node:test";
import assert from "node:assert/strict";
import { budgetStatus, classifyCommand, isUsageLimitError } from "../src/policy.mjs";

test("blocks destructive and deployment commands", () => {
  assert.equal(classifyCommand("rm -rf /", "/work/issue-1").decision, "decline");
  assert.equal(classifyCommand("terraform apply", "/work/issue-1").decision, "decline");
  assert.equal(classifyCommand("gh pr merge 3", "/work/issue-1").decision, "decline");
});

test("allows a workspace-local test", () => {
  assert.equal(classifyCommand("npm test", "/work/issue-1").decision, "accept");
});

test("budget fails closed at token limit", () => {
  const config = { dailyTokenLimit: 100, dailyCostLimitUsd: 10, estimatedUsdPerMillionTokens: 1 };
  assert.equal(budgetStatus(config, { tokens: 100, estimatedUsd: 0 }).allowed, false);
});

test("uses the signed-in account percentage instead of synthetic API cost when available", () => {
  const config = { dailyTokenLimit: 100, dailyCostLimitUsd: 10, estimatedUsdPerMillionTokens: 1, accountUsagePausePercent: 95, accountUsageMaxAgeMs: 300_000 };
  const current = { usedPercent: 52, ordinaryUsageAllowed: true, updatedAt: "2026-11-02T10:00:00.000Z", resetsAt: Date.parse("2026-11-04T10:00:00.000Z") / 1000 };
  const now = Date.parse("2026-11-02T10:01:00.000Z");
  assert.equal(budgetStatus(config, { tokens: 999999, estimatedUsd: 999 }, current, now).allowed, true);
  assert.equal(budgetStatus(config, {}, { ...current, usedPercent: 95 }, now).allowed, false);
  assert.equal(budgetStatus(config, {}, { ...current, ordinaryUsageAllowed: false }, now).allowed, false);
  assert.equal(budgetStatus(config, {}, { ...current, updatedAt: "2026-11-02T09:00:00.000Z" }, now).allowed, false);
  assert.equal(budgetStatus(config, {}, null, now).allowed, false);
});

test("recognizes plan usage limit errors for automatic pause", () => {
  const error = new Error("request failed");
  error.codexErrorInfo = "usageLimitExceeded";
  assert.equal(isUsageLimitError(error), true);
  assert.equal(isUsageLimitError(new Error("ordinary test failure")), false);
});

