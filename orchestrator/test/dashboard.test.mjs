import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { DashboardWriter, buildDashboardModel, renderDashboard, safeJson } from "../src/dashboard.mjs";

const config = {
  githubOwner: "demo-org",
  githubRepo: "demo-repo",
  githubProjectUrl: "https://github.com/orgs/demo-org/projects/1",
  dailyTokenLimit: 2000000,
  dailyCostLimitUsd: 25,
  maxParallel: 3,
  dashboardIntervalMs: 10000,
};

test("builds a workspace-safe dashboard model", () => {
  const state = {
    jobs: {
      42: {
        issue: 42,
        title: "관제판",
        status: "implementing",
        workspace: "/Users/person/private/issue-42",
        error: "secret raw failure",
        tokens: 1200,
        updatedAt: "2026-11-01T10:00:00.000Z",
      },
    },
    approvals: {},
    usage: { "2026-11-01": { tokens: 1200, estimatedUsd: 0.01 } },
  };
  const model = buildDashboardModel(state, config, { now: new Date("2026-11-01T11:00:00.000Z"), activeCount: 1 });
  const serialized = JSON.stringify(model);
  assert.equal(model.jobs[0].workspaceState, "격리됨");
  assert.doesNotMatch(serialized, /\/Users\/person|secret raw failure/);
});

test("escapes inline JSON and renders data only through textContent", () => {
  const attack = "</script><img src=x onerror=alert(1)>&\u2028";
  const encoded = safeJson({ attack });
  assert.doesNotMatch(encoded, /<\/script>|<img|&/);
  assert.match(encoded, /\\u003c/);
  const html = renderDashboard({
    generatedAt: "2026-11-01T00:00:00.000Z",
    repository: attack,
    projectUrl: null,
    activeCount: 0,
    maxParallel: 3,
    deploymentActive: false,
    usage: { tokens: 0, tokenLimit: 1, estimatedUsd: 0, costLimitUsd: 0 },
    summary: { approval: 0, blocked: 0, active: 0, done: 0 },
    jobs: [],
  });
  assert.doesNotMatch(html, /<img src=x|<\/script><img/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /\.textContent=/);
});

test("marks dashboard output with the orchestrator heartbeat generator", () => {
  const html = renderDashboard(buildDashboardModel({ jobs: {}, usage: {} }, config, { now: new Date("2026-11-01T11:00:00.000Z") }));
  assert.match(html, /<meta name="generator" content="social-drive-orchestrator-v1">/);
  assert.match(html, /<meta http-equiv="refresh" content="10">/);
  assert.match(html, /const staleAfterMs=30000/);
  assert.match(html, /heartbeatAge>staleAfterMs/);
  assert.match(html, /heartbeat가 '\+Math\.ceil\(staleAfterMs\/1000\)\+'초 이상/);
  assert.match(html, /마지막 저장 시점의 스냅샷/);
});

test("derives refresh and stale thresholds from the configured dashboard interval", () => {
  const slow = buildDashboardModel({ jobs: {}, approvals: {}, usage: {} }, { ...config, dashboardIntervalMs: 60000 }, { now: new Date("2026-11-01T11:00:00.000Z") });
  assert.equal(slow.refreshSeconds, 60);
  assert.equal(slow.staleAfterMs, 180000);
  const html = renderDashboard(slow);
  assert.match(html, /<meta http-equiv="refresh" content="60">/);
  assert.match(html, /const staleAfterMs=180000/);
});

test("atomically replaces the local dashboard file without a server", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-dashboard-"));
  const file = path.join(directory, "status-dashboard.html");
  const store = { state: { jobs: {}, approvals: {}, usage: {} } };
  const writer = new DashboardWriter({ file, store, config, runtime: () => ({ activeCount: 0, deploymentActive: false }) });
  await writer.write();
  const first = await fs.readFile(file, "utf8");
  store.state.jobs["1"] = { issue: 1, title: "새 작업", status: "planning", updatedAt: "2026-11-01T10:00:00.000Z" };
  await writer.write();
  const second = await fs.readFile(file, "utf8");
  assert.notEqual(first, second);
  assert.match(second, /Development Control Center/);
  assert.deepEqual((await fs.readdir(directory)).sort(), ["status-dashboard.html"]);
  await fs.rm(directory, { recursive: true, force: true });
});
