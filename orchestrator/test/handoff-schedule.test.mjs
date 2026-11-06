import test from "node:test";
import assert from "node:assert/strict";
import { prHandoffSchedule, recordHandoffFailure } from "../src/handoff-schedule.mjs";

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

test("never downgrades a durable pending merge approval after notification failure", () => {
  const state = {
    jobs: { "15": { status: "awaiting_merge_approval", pr: 22 } },
    approvals: { "pr-22": { status: "pending" } },
  };
  assert.equal(recordHandoffFailure(state, "15", "Discord offline", new Date(0)), "preserved");
  assert.equal(state.jobs["15"].status, "awaiting_merge_approval");
  assert.equal(state.jobs["15"].error, undefined);
});

test("returns an unfinished publishing handoff to the durable deferred queue", () => {
  const state = { jobs: { "15": { status: "publishing" } }, approvals: {} };
  assert.equal(recordHandoffFailure(state, "15", "GitHub offline", new Date(0)), "deferred");
  assert.equal(state.jobs["15"].status, "handoff_deferred");
  assert.equal(state.jobs["15"].updatedAt, "1970-01-01T00:00:00.000Z");
});
