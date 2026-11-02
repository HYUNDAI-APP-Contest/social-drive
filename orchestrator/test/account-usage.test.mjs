import test from "node:test";
import assert from "node:assert/strict";
import { recordAccountRateLimit, recordAccountTokenSnapshot, threadTotalTokens } from "../src/account-usage.mjs";

test("extracts the actual cumulative thread total from app-server v2 events", () => {
  assert.equal(threadTotalTokens({
    event: "thread/tokenUsage/updated",
    params: { tokenUsage: { total: { totalTokens: 123456 } } },
  }), 123456);
  assert.equal(threadTotalTokens({ event: "turn/completed", params: {} }), null);
});

test("stores account-wide daily and lifetime token snapshots", () => {
  const state = {};
  recordAccountTokenSnapshot(state, {
    summary: { lifetimeTokens: 1624957643 },
    dailyUsageBuckets: [{ startDate: "2026-11-02", tokens: 138499664 }],
  }, { day: "2026-11-02" });
  assert.equal(state.accountUsage.lifetimeTokens, 1624957643);
  assert.equal(state.accountUsage.todayTokens, 138499664);
});

test("emits 30/60/90 once per real rate-limit window", () => {
  const state = { outbox: {} };
  const first = recordAccountRateLimit(state, {
    ordinaryUsageAllowed: true,
    rateLimits: { primary: { usedPercent: 52, windowDurationMins: 10080, resetsAt: 2000 } },
  }, { now: 1000 });
  assert.deepEqual(first.claimed, [30]);
  const repeat = recordAccountRateLimit(state, { rateLimits: { primary: { usedPercent: 55, windowDurationMins: 10080, resetsAt: 2000 } } }, { now: 1001 });
  assert.deepEqual(repeat.claimed, []);
  const sixty = recordAccountRateLimit(state, { rateLimits: { primary: { usedPercent: 65, windowDurationMins: 10080, resetsAt: 2000 } } }, { now: 1002 });
  assert.deepEqual(sixty.claimed, [60]);
  const reset = recordAccountRateLimit(state, { rateLimits: { primary: { usedPercent: 31, windowDurationMins: 10080, resetsAt: 3000 } } }, { now: 1003 });
  assert.deepEqual(reset.claimed, [30]);
  assert.equal(Object.values(state.outbox).length, 3);
});
