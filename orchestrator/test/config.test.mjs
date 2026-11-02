import test from "node:test";
import assert from "node:assert/strict";
import { loadConfig } from "../src/config.mjs";

test("defaults to three parallel agents", () => {
  const old = process.env.MAX_PARALLEL_AGENTS; delete process.env.MAX_PARALLEL_AGENTS;
  try { assert.equal(loadConfig({ requireSecrets: false }).maxParallel, 3); }
  finally { if (old === undefined) delete process.env.MAX_PARALLEL_AGENTS; else process.env.MAX_PARALLEL_AGENTS = old; }
});

test("defaults to a local ten-second dashboard without detailed channel spam", () => {
  const interval = process.env.DASHBOARD_INTERVAL_SECONDS;
  delete process.env.DASHBOARD_INTERVAL_SECONDS;
  try {
    const config = loadConfig({ requireSecrets: false });
    assert.equal(config.dashboardIntervalMs, 10_000);
    assert.match(config.dashboardPath, /status-dashboard\.html$/);
  } finally {
    if (interval === undefined) delete process.env.DASHBOARD_INTERVAL_SECONDS; else process.env.DASHBOARD_INTERVAL_SECONDS = interval;
  }
});

test("rejects zero parallel agents", () => {
  const old = process.env.MAX_PARALLEL_AGENTS; process.env.MAX_PARALLEL_AGENTS = "0";
  try { assert.throws(() => loadConfig({ requireSecrets: false }), /MAX_PARALLEL_AGENTS/); }
  finally { if (old === undefined) delete process.env.MAX_PARALLEL_AGENTS; else process.env.MAX_PARALLEL_AGENTS = old; }
});

test("defaults the signed-in account safety threshold to 95 percent", () => {
  const old = process.env.ACCOUNT_USAGE_PAUSE_PERCENT; delete process.env.ACCOUNT_USAGE_PAUSE_PERCENT;
  try { assert.equal(loadConfig({ requireSecrets: false }).accountUsagePausePercent, 95); }
  finally { if (old === undefined) delete process.env.ACCOUNT_USAGE_PAUSE_PERCENT; else process.env.ACCOUNT_USAGE_PAUSE_PERCENT = old; }
});

