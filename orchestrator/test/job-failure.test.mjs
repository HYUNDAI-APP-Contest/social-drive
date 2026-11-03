import test from "node:test";
import assert from "node:assert/strict";
import { classifyJobFailure, failureNotice, isReviewChangesRequiredError } from "../src/job-failure.mjs";

test("classifies review feedback separately from runtime recovery", () => {
  const error = new Error("센티넬 검토를 2회 통과하지 못했습니다.");
  assert.equal(isReviewChangesRequiredError(error), true);
  assert.equal(classifyJobFailure({ usageLimited: false, reviewChangesRequired: true, attempts: 2, maxRetries: 3 }), "review_retry");
  assert.equal(classifyJobFailure({ usageLimited: false, reviewChangesRequired: true, attempts: 4, maxRetries: 3 }), "review_blocked");
  assert.equal(classifyJobFailure({ usageLimited: false, reviewChangesRequired: false, attempts: 2, maxRetries: 3 }), "retry");
  assert.equal(classifyJobFailure({ usageLimited: false, reviewChangesRequired: false, attempts: 4, maxRetries: 3 }), "failed");
});

test("review notices never claim a system failure or automatic recovery", () => {
  const retry = failureNotice({ issue: 15, status: "review_retry", attempts: 2, maxRetries: 3, usageLimitCooldownMs: 60_000 });
  const blocked = failureNotice({ issue: 15, status: "review_blocked", attempts: 4, maxRetries: 3, usageLimitCooldownMs: 60_000 });
  assert.match(retry, /검토 수정 주기/);
  assert.doesNotMatch(retry, /자동 복구|❌/);
  assert.match(blocked, /agent:ready/);
  assert.doesNotMatch(blocked, /자동 복구 실패/);
});
