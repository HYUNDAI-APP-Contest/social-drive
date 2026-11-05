import test from "node:test";
import assert from "node:assert/strict";
import { prHandoffSchedule } from "../src/handoff-schedule.mjs";

test("defers PR handoff before 08:00 KST", () => {
  assert.deepEqual(prHandoffSchedule(new Date("2026-11-05T15:58:00.000Z"), 8), {
    open: false,
    nextAt: "2026-11-05T23:00:00.000Z",
  });
  assert.equal(prHandoffSchedule(new Date("2026-11-05T22:59:59.999Z"), 8).open, false);
});

test("opens PR handoff at and after 08:00 KST", () => {
  assert.equal(prHandoffSchedule(new Date("2026-11-05T23:00:00.000Z"), 8).open, true);
  assert.equal(prHandoffSchedule(new Date("2026-11-06T14:59:59.999Z"), 8).open, true);
});
