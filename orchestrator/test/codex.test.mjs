import test from "node:test";
import assert from "node:assert/strict";
import { CodexAppServer, agentText } from "../src/codex.mjs";

test("extracts only agent messages for role handoff", () => {
  const turn = { items: [
    { type: "reasoning", content: ["private"] },
    { type: "agentMessage", text: "first" },
    { type: "commandExecution", aggregatedOutput: "secret-like command output" },
    { type: "agentMessage", text: "second" },
  ] };
  assert.equal(agentText(turn), "first\n\nsecond");
});

test("requires a live valid seven-day rate-limit response before admission", async () => {
  const server = new CodexAppServer({ appServerRequestTimeoutMs: 1000 }, { workspace: "." });
  server.request = async (method) => method === "account/usage/read"
    ? null
    : { ordinaryUsageAllowed: true, rateLimits: { primary: { usedPercent: 1, windowDurationMins: 300 } } };
  await assert.rejects(server.readAccountState(), /valid seven-day limit/);
});

test("always cleans up the app-server child when pre-turn admission fails", async () => {
  let killed = 0;
  const server = new CodexAppServer({ appServerRequestTimeoutMs: 1000 }, { workspace: "." });
  server.connect = async () => { server.child = { kill: () => { killed += 1; } }; };
  server.readAccountState = async () => { throw new Error("live usage unavailable"); };
  await assert.rejects(server.start("test"), /live usage unavailable/);
  assert.equal(killed, 1);
});
