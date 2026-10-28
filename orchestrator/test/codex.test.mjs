import test from "node:test";
import assert from "node:assert/strict";
import { agentText } from "../src/codex.mjs";

test("extracts only agent messages for role handoff", () => {
  const turn = { items: [
    { type: "reasoning", content: ["private"] },
    { type: "agentMessage", text: "first" },
    { type: "commandExecution", aggregatedOutput: "secret-like command output" },
    { type: "agentMessage", text: "second" },
  ] };
  assert.equal(agentText(turn), "first\n\nsecond");
});
