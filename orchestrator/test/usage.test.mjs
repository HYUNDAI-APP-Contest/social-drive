import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { Store } from "../src/store.mjs";
import { recordUsage } from "../src/usage.mjs";

test("atomically persists once-only milestones across concurrent agent usage", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-usage-"));
  const store = new Store(path.join(directory, "state.json"));
  await store.load();
  await store.update((state) => { state.jobs["42"] = { issue: 42, tokens: 0 }; });
  const add = (tokens, now) => store.update((state) => recordUsage(state, {
    day: "2026-11-01",
    jobKey: "42",
    tokens,
    dailyTokenLimit: 1000,
    estimatedUsdPerMillionTokens: 10,
    now,
  }));

  await Promise.all([add(350, 1000), add(300, 1001), add(300, 1002)]);

  assert.equal(store.state.usage["2026-11-01"].tokens, 950);
  assert.deepEqual(store.state.usage["2026-11-01"].milestones, [30, 60, 90]);
  assert.equal(store.state.jobs["42"].tokens, 950);
  const notices = Object.values(store.state.outbox).filter((entry) => entry.destination === "discord-report");
  assert.equal(notices.length, 3);
  assert.equal(notices.filter((entry) => entry.payload.content.includes("30%")).length, 1);
  assert.equal(notices.filter((entry) => entry.payload.content.includes("60%")).length, 1);
  assert.equal(notices.filter((entry) => entry.payload.content.includes("90%")).length, 1);
  await fs.rm(directory, { recursive: true, force: true });
});

test("groups multiple milestones crossed by one usage event into one durable notice", () => {
  const state = { usage: {}, jobs: {}, outbox: {} };
  const result = recordUsage(state, {
    day: "2026-11-01",
    jobKey: "42",
    tokens: 910,
    dailyTokenLimit: 1000,
    estimatedUsdPerMillionTokens: 10,
    now: 1000,
  });
  assert.deepEqual(result.claimed, [30, 60, 90]);
  const entries = Object.values(state.outbox);
  assert.equal(entries.length, 1);
  assert.match(entries[0].payload.content, /30% · 60% · 90%/);
});
