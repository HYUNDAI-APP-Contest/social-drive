import test from "node:test";
import assert from "node:assert/strict";
import { loadConfig } from "../src/config.mjs";

test("defaults to three parallel agents", () => {
  const old = process.env.MAX_PARALLEL_AGENTS; delete process.env.MAX_PARALLEL_AGENTS;
  try { assert.equal(loadConfig({ requireSecrets: false }).maxParallel, 3); }
  finally { if (old === undefined) delete process.env.MAX_PARALLEL_AGENTS; else process.env.MAX_PARALLEL_AGENTS = old; }
});

test("rejects zero parallel agents", () => {
  const old = process.env.MAX_PARALLEL_AGENTS; process.env.MAX_PARALLEL_AGENTS = "0";
  try { assert.throws(() => loadConfig({ requireSecrets: false }), /MAX_PARALLEL_AGENTS/); }
  finally { if (old === undefined) delete process.env.MAX_PARALLEL_AGENTS; else process.env.MAX_PARALLEL_AGENTS = old; }
});

