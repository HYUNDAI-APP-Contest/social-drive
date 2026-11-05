import test from "node:test";
import assert from "node:assert/strict";
import { recordAccountRateLimit, recordAccountTokenSnapshot, selectSevenDayRateLimit, threadTotalTokens } from "../src/account-usage.mjs";

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

test("selects the explicit seven-day window and ignores delayed older snapshots", () => {
  const response = {
    rateLimitsByLimitId: {
      codex: {
        primary: { usedPercent: 90, windowDurationMins: 300, resetsAt: 1500 },
        secondary: { usedPercent: 52, windowDurationMins: 10080, resetsAt: 3000 },
      },
    },
  };
  assert.equal(selectSevenDayRateLimit(response).window.usedPercent, 52);
  const state = { outbox: {} };
  recordAccountRateLimit(state, response, { now: 2000 });
  recordAccountRateLimit(state, { rateLimits: { primary: { usedPercent: 80, windowDurationMins: 10080, resetsAt: 2000 } } }, { now: 1000 });
  assert.equal(state.accountUsage.usedPercent, 52);
  assert.equal(state.accountUsage.resetsAt, 3000);
});

test("tolerates small reset timestamp jitter without making live usage stale", () => {
  const state = {
    accountUsage: { usedPercent: 58, resetsAt: 1793774170, updatedAt: "2026-11-03T06:00:00.000Z", milestones: [30] },
    outbox: {},
  };
  recordAccountRateLimit(state, {
    ordinaryUsageAllowed: true,
    rateLimits: { primary: { usedPercent: 62, windowDurationMins: 10080, resetsAt: 1793774166 } },
  }, { now: Date.parse("2026-11-03T14:00:00.000Z") });
  assert.equal(state.accountUsage.usedPercent, 62);
  assert.equal(state.accountUsage.resetsAt, 1793774170);
  assert.equal(state.accountUsage.updatedAt, "2026-11-03T14:00:00.000Z");
  assert.deepEqual(state.accountUsage.milestones, [30, 60]);
});

test("does not label a non-seven-day window as the account budget", () => {
  assert.equal(selectSevenDayRateLimit({ rateLimits: { primary: { usedPercent: 20, windowDurationMins: 300 } } }), null);
});

test("persists an authoritative denial even without a seven-day window", () => {
  const state = { accountUsage: { usedPercent: 20, updatedAt: "2026-11-02T00:00:00.000Z" }, outbox: {} };
  recordAccountRateLimit(state, { ordinaryUsageAllowed: false, rateLimits: { primary: { usedPercent: 1, windowDurationMins: 300 } } }, { now: Date.parse("2026-11-02T00:01:00.000Z") });
  assert.equal(state.accountUsage.ordinaryUsageAllowed, false);
  assert.equal(state.accountUsage.usedPercent, 20);
});

test("never lets delayed or lower same-window snapshots reopen admission", () => {
  const state = {
    accountUsage: {
      usedPercent: 96,
      remainingPercent: 4,
      ordinaryUsageAllowed: false,
      resetsAt: 3000,
      updatedAt: "2026-11-02T10:00:00.000Z",
      milestones: [30, 60, 90],
    },
    outbox: {},
  };
  const lower = { ordinaryUsageAllowed: true, rateLimits: { primary: { usedPercent: 40, windowDurationMins: 10080, resetsAt: 3000 } } };
  recordAccountRateLimit(state, lower, { now: Date.parse("2026-11-02T09:00:00.000Z") });
  assert.equal(state.accountUsage.usedPercent, 96);
  assert.equal(state.accountUsage.ordinaryUsageAllowed, false);
  recordAccountRateLimit(state, lower, { now: Date.parse("2026-11-02T11:00:00.000Z") });
  assert.equal(state.accountUsage.usedPercent, 96);
  assert.equal(state.accountUsage.ordinaryUsageAllowed, false);
  const reset = { ordinaryUsageAllowed: true, rateLimits: { primary: { usedPercent: 5, windowDurationMins: 10080, resetsAt: 4000 } } };
  recordAccountRateLimit(state, reset, { now: Date.parse("2026-11-02T12:00:00.000Z") });
  assert.equal(state.accountUsage.usedPercent, 5);
  assert.equal(state.accountUsage.ordinaryUsageAllowed, true);
});

test("latches a no-window denial until a later seven-day window is proven", () => {
  const state = { outbox: {} };
  recordAccountRateLimit(state, {
    ordinaryUsageAllowed: false,
    rateLimits: { primary: { usedPercent: 1, windowDurationMins: 300, resetsAt: 100 } },
  }, { now: 1000 });
  assert.equal(state.accountUsage.ordinaryUsageAllowed, false);
  assert.equal(state.accountUsage.denyLatched, true);

  recordAccountRateLimit(state, {
    ordinaryUsageAllowed: true,
    rateLimits: { primary: { usedPercent: 40, windowDurationMins: 10080, resetsAt: 3000 } },
  }, { now: 1001 });
  assert.equal(state.accountUsage.ordinaryUsageAllowed, false);
  assert.equal(state.accountUsage.denyWindowResetsAt, 3000);

  recordAccountRateLimit(state, {
    ordinaryUsageAllowed: true,
    rateLimits: { primary: { usedPercent: 5, windowDurationMins: 10080, resetsAt: 4000 } },
  }, { now: 3_001_000 });
  assert.equal(state.accountUsage.ordinaryUsageAllowed, true);
  assert.equal(state.accountUsage.denyLatched, undefined);
});

test("a repeated no-window denial clears an obsolete reset baseline", () => {
  const state = {
    accountUsage: {
      usedPercent: 5,
      ordinaryUsageAllowed: false,
      denyLatched: true,
      denyWindowResetsAt: 3000,
      resetsAt: 4000,
      updatedAt: new Date(3_000_000).toISOString(),
      milestones: [],
    },
    outbox: {},
  };
  recordAccountRateLimit(state, {
    ordinaryUsageAllowed: false,
    rateLimits: { primary: { usedPercent: 1, windowDurationMins: 300 } },
  }, { now: 3_001_000 });
  assert.equal(state.accountUsage.denyWindowResetsAt, undefined);
  recordAccountRateLimit(state, {
    ordinaryUsageAllowed: true,
    rateLimits: { primary: { usedPercent: 10, windowDurationMins: 10080, resetsAt: 5000 } },
  }, { now: 3_001_001 });
  assert.equal(state.accountUsage.ordinaryUsageAllowed, false);
  assert.equal(state.accountUsage.denyWindowResetsAt, 5000);
});
