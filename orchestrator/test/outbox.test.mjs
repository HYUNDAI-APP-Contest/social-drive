import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { DurableOutbox, enqueueOutbox } from "../src/outbox.mjs";
import { Store } from "../src/store.mjs";

async function harness() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-outbox-"));
  const file = path.join(directory, "state.json");
  const store = new Store(file);
  await store.load();
  return { directory, file, store };
}

test("coalesces destination work by key and delivers the newest payload", async () => {
  const ctx = await harness();
  let now = 1000;
  const delivered = [];
  const outbox = new DurableOutbox({
    store: ctx.store,
    handlers: { "discord-report": async (payload) => delivered.push(payload) },
    clock: () => now,
  });
  await outbox.enqueue({ key: "daily:2026-11-01", destination: "discord-report", payload: { content: "30%" } });
  now += 1;
  await outbox.enqueue({ key: "daily:2026-11-01", destination: "discord-report", payload: { content: "30% · 60%" } });
  await outbox.drain();
  assert.deepEqual(delivered, [{ content: "30% · 60%" }]);
  assert.equal(ctx.store.state.outbox["daily:2026-11-01"].state, "delivered");
  await fs.rm(ctx.directory, { recursive: true, force: true });
});

test("backs off failed delivery and retries after the durable deadline", async () => {
  const ctx = await harness();
  let now = 1000;
  let attempts = 0;
  const outbox = new DurableOutbox({
    store: ctx.store,
    handlers: { "github-progress": async () => { attempts += 1; if (attempts === 1) throw new Error("offline"); } },
    clock: () => now,
    random: () => 0.5,
  });
  await outbox.enqueue({ key: "issue:1:progress", destination: "github-progress", payload: { issue: 1 } });
  await outbox.drain();
  assert.equal(ctx.store.state.outbox["issue:1:progress"].state, "pending");
  assert.equal(ctx.store.state.outbox["issue:1:progress"].nextAttemptAt, 2000);
  await outbox.drain();
  assert.equal(attempts, 1);
  now = 2000;
  await outbox.drain();
  assert.equal(attempts, 2);
  assert.equal(ctx.store.state.outbox["issue:1:progress"].state, "delivered");
  await fs.rm(ctx.directory, { recursive: true, force: true });
});

test("recovers in-flight deliveries after restart without accepting arbitrary destinations", async () => {
  const ctx = await harness();
  await ctx.store.update((state) => {
    enqueueOutbox(state, { key: "issue:2:labels", destination: "github-labels", payload: { issue: 2 } }, 1000);
    state.outbox["issue:2:labels"].state = "sending";
  });
  const restarted = new Store(ctx.file);
  await restarted.load();
  assert.equal(restarted.state.outbox["issue:2:labels"].state, "pending");
  assert.equal(restarted.state.outbox["issue:2:labels"].nextAttemptAt, 0);
  assert.throws(() => enqueueOutbox(restarted.state, { key: "bad", destination: "https://attacker.invalid", payload: {} }), /Unsupported/);
  await fs.rm(ctx.directory, { recursive: true, force: true });
});

test("recovers a publishing handoff as deferred without rerunning implementation", async () => {
  const ctx = await harness();
  await ctx.store.update((state) => {
    state.jobs["15"] = {
      status: "publishing", commit: "a".repeat(40), workspace: "/safe/workspace", branch: "agent/issue-15",
    };
  });
  const restarted = new Store(ctx.file);
  await restarted.load();
  assert.equal(restarted.state.jobs["15"].status, "handoff_deferred");
  await fs.rm(ctx.directory, { recursive: true, force: true });
});

test("recovers every interrupted worker stage without restarting gated or terminal jobs", async () => {
  const ctx = await harness();
  const interruptedStages = ["preparing", "running", "planning", "implementing", "reviewing", "reworking"];
  const preservedStatuses = [
    "awaiting_merge_approval", "awaiting_deploy_approval", "merged", "deployed", "rejected", "deploy_rejected",
    "failed", "review_blocked", "merge_failed", "deploy_failed", "merge_approval_expired",
    "deploy_approval_expired", "removed_from_queue",
  ];
  await ctx.store.update((state) => {
    interruptedStages.forEach((status) => {
      state.jobs[`interrupted-${status}`] = { status, marker: `metadata-${status}` };
    });
    state.jobs["publishing-incomplete"] = { status: "publishing", commit: "b".repeat(40), marker: "metadata-publishing" };
    preservedStatuses.forEach((status) => {
      state.jobs[`preserved-${status}`] = { status, marker: `metadata-${status}` };
    });
  });

  const restarted = new Store(ctx.file);
  await restarted.load();

  assert.equal(restarted.state.jobs["interrupted-preparing"].status, "retry");
  assert.equal(restarted.state.jobs["interrupted-running"].status, "retry");
  assert.equal(restarted.state.jobs["interrupted-planning"].status, "retry");
  assert.equal(restarted.state.jobs["interrupted-implementing"].status, "retry");
  assert.equal(restarted.state.jobs["interrupted-reviewing"].status, "retry");
  assert.equal(restarted.state.jobs["interrupted-reworking"].status, "retry");
  assert.equal(restarted.state.jobs["publishing-incomplete"].status, "retry");
  interruptedStages.forEach((status) => {
    assert.equal(restarted.state.jobs[`interrupted-${status}`].marker, `metadata-${status}`);
  });
  assert.equal(restarted.state.jobs["publishing-incomplete"].marker, "metadata-publishing");
  preservedStatuses.forEach((status) => {
    assert.equal(restarted.state.jobs[`preserved-${status}`].status, status);
    assert.equal(restarted.state.jobs[`preserved-${status}`].marker, `metadata-${status}`);
  });
  await fs.rm(ctx.directory, { recursive: true, force: true });
});

test("accepts only the fixed Discord approval outbox destination", async () => {
  const ctx = await harness();
  const entry = enqueueOutbox(ctx.store.state, {
    key: "approval-card:pr-22", destination: "discord-approval", payload: { approval: { id: "pr-22" } },
  }, 1000);
  assert.equal(entry.destination, "discord-approval");
  await fs.rm(ctx.directory, { recursive: true, force: true });
});
