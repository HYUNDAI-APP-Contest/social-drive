import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { buildProgressSnapshot, renderAgentStatus, renderProgressComment } from "../src/control-center.mjs";
import { buildDashboardModel } from "../src/dashboard.mjs";
import { enqueueOutbox } from "../src/outbox.mjs";
import { reconcileReadyIssues } from "../src/ready-queue.mjs";
import { Store } from "../src/store.mjs";

const config = {
  githubOwner: "demo",
  githubRepo: "social-drive",
  githubProjectUrl: "https://github.com/orgs/demo/projects/1",
  dailyTokenLimit: 1000,
  dailyCostLimitUsd: 10,
  maxParallel: 3,
};

const enqueueControlSnapshot = (state, key, now) => {
  const snapshot = buildProgressSnapshot(state.jobs[key], { dailyUsage: {}, dailyTokenLimit: 1000, now });
  enqueueOutbox(state, { key: `issue:${key}:progress`, destination: "github-progress", payload: { issue: Number(key), body: renderProgressComment(snapshot) } }, now.getTime());
  enqueueOutbox(state, { key: `issue:${key}:labels`, destination: "github-labels", payload: { issue: Number(key), labels: snapshot.labels } }, now.getTime());
  enqueueOutbox(state, { key: `issue:${key}:project`, destination: "github-project", payload: { snapshot } }, now.getTime());
};

test("all ready issues become visible while only free slots start", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-ready-"));
  const store = new Store(path.join(directory, "state.json"));
  await store.load();
  const issues = [1, 2, 3, 4].map((number) => ({ number, node_id: `I_${number}`, title: `작업 ${number}` }));
  const started = await reconcileReadyIssues({
    issues,
    store,
    activeKeys: new Set(["99", "98"]),
    maxParallel: 3,
    now: new Date("2026-11-01T10:00:00.000Z"),
    enqueueSnapshot: (state, key, now) => enqueueOutbox(state, {
      key: `issue:${key}:progress`,
      destination: "github-progress",
      payload: { issue: Number(key), status: "ready" },
    }, now.getTime()),
  });

  assert.equal(started.length, 1);
  assert.equal(started[0].number, 1);
  assert.deepEqual(Object.keys(store.state.jobs).sort(), ["1", "2", "3", "4"]);
  assert.ok(Object.values(store.state.jobs).every((job) => job.status === "ready"));
  assert.equal(Object.keys(store.state.outbox).length, 4);

  const restarted = new Store(store.file);
  await restarted.load();
  assert.equal(restarted.state.jobs["4"].status, "ready");
  await fs.rm(directory, { recursive: true, force: true });
});

test("a closed or unlabeled idle issue leaves Ready everywhere and can be re-added", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-stale-ready-"));
  const store = new Store(path.join(directory, "state.json"));
  await store.load();
  const now = new Date("2026-11-01T10:00:00.000Z");
  const issues = [1, 2].map((number) => ({ number, node_id: `I_${number}`, title: `작업 ${number}` }));

  await reconcileReadyIssues({ issues, store, activeKeys: new Set(), maxParallel: 0, now, enqueueSnapshot: enqueueControlSnapshot });
  await reconcileReadyIssues({ issues: [issues[0]], store, activeKeys: new Set(), maxParallel: 0, now: new Date("2026-11-01T10:01:00.000Z"), enqueueSnapshot: enqueueControlSnapshot });

  assert.equal(store.state.jobs["1"].status, "ready");
  assert.equal(store.state.jobs["2"].status, "removed_from_queue");
  const project = store.state.outbox["issue:2:project"].payload.snapshot;
  assert.equal(project.status, "Cancelled");
  assert.deepEqual(store.state.outbox["issue:2:labels"].payload.labels, ["agent:stage:cancelled", "agent:persona:orchestrator"]);
  assert.match(store.state.outbox["issue:2:progress"].payload.body, /Cancelled/);

  const dashboard = buildDashboardModel(store.state, config, { now: new Date("2026-11-01T10:02:00.000Z") });
  assert.deepEqual(dashboard.jobs.map((job) => job.status).sort(), ["Cancelled", "Ready"]);
  const status = renderAgentStatus({
    jobs: Object.values(store.state.jobs), approvals: [], usage: {}, config, activeCount: 0,
    now: new Date("2026-11-01T10:02:00.000Z"),
  });
  assert.match(status, /대기 1/);
  assert.match(status, /\[Cancelled\] #2/);

  await reconcileReadyIssues({ issues, store, activeKeys: new Set(), maxParallel: 0, now: new Date("2026-11-01T10:03:00.000Z"), enqueueSnapshot: enqueueControlSnapshot });
  assert.equal(store.state.jobs["2"].status, "ready");
  assert.equal(store.state.jobs["2"].attempts, 0);
  await fs.rm(directory, { recursive: true, force: true });
});

test("stale reconciliation never rewrites active or terminal jobs", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-active-ready-"));
  const store = new Store(path.join(directory, "state.json"));
  await store.load();
  store.state.jobs = {
    2: { issue: 2, title: "starting", status: "ready", attempts: 0 },
    3: { issue: 3, title: "done", status: "merged", attempts: 1 },
  };
  await store.save();

  await reconcileReadyIssues({ issues: [], store, activeKeys: new Set(["2"]), maxParallel: 3, now: new Date("2026-11-01T10:00:00.000Z"), enqueueSnapshot: enqueueControlSnapshot });

  assert.equal(store.state.jobs["2"].status, "ready");
  assert.equal(store.state.jobs["3"].status, "merged");
  assert.equal(Object.keys(store.state.outbox).length, 0);
  await fs.rm(directory, { recursive: true, force: true });
});
