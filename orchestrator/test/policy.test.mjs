import test from "node:test";
import assert from "node:assert/strict";
import { budgetStatus, classifyCommand } from "../src/policy.mjs";

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

