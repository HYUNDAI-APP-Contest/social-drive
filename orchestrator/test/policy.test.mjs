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
  const config = { dailyTokenLimit: 100, dailyCostLimitUsd: 10, estimatedUsdPerMillionTokens: 1, accountUsagePausePercent: 95 };
  assert.equal(budgetStatus(config, { tokens: 999999, estimatedUsd: 999 }, { usedPercent: 52, ordinaryUsageAllowed: true }).allowed, true);
  assert.equal(budgetStatus(config, { tokens: 0, estimatedUsd: 0 }, { usedPercent: 95, ordinaryUsageAllowed: true }).allowed, false);
  assert.equal(budgetStatus(config, { tokens: 0, estimatedUsd: 0 }, { usedPercent: 1, ordinaryUsageAllowed: false }).allowed, false);
});

test("recognizes plan usage limit errors for automatic pause", () => {
  const error = new Error("request failed");
  error.codexErrorInfo = "usageLimitExceeded";
  assert.equal(isUsageLimitError(error), true);
  assert.equal(isUsageLimitError(new Error("ordinary test failure")), false);
});

