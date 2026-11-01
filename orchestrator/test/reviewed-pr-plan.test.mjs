import test from "node:test";
import assert from "node:assert/strict";
import { planReviewedPullRequest } from "../../scripts/reviewed-pr-plan.mjs";

const repository = "HYUNDAI-APP-Contest/social-drive";
const expected = "a".repeat(40);
const branch = "agent/issue-safe";
const pull = (overrides = {}) => ({
  state: "open",
  merged_at: null,
  html_url: "https://github.com/HYUNDAI-APP-Contest/social-drive/pull/1",
  base: { ref: "main" },
  head: { ref: branch, sha: expected, repo: { full_name: repository } },
  ...overrides,
});

test("reuses only an exact official-repo open PR targeting main", () => {
  const result = planReviewedPullRequest({ pulls: [[pull()]], refs: {}, repository, branch, expected });
  assert.deepEqual(result, { action: "existing", branch, url: pull().html_url });
});

test("recognizes normal ancestry and an old paginated squash merge", () => {
  assert.equal(planReviewedPullRequest({ pulls: [], refs: {}, repository, branch, expected, inMain: true }).action, "merged");
  const noise = Array.from({ length: 100 }, (_, index) => pull({ head: { ...pull().head, sha: String(index) } }));
  const merged = pull({ state: "closed", merged_at: "2026-11-01T00:00:00Z", html_url: "merged" });
  assert.deepEqual(
    planReviewedPullRequest({ pulls: [noise, [merged]], refs: {}, repository, branch, expected }),
    { action: "merged", branch, url: "merged" },
  );
});

test("closed exact PR chooses a unique replacement across any-base collisions", () => {
  const closed = pull({ state: "closed", html_url: "closed" });
  const replacement = `${branch}-review-${expected.slice(0, 12)}`;
  const otherBase = pull({ base: { ref: "develop" }, head: { ...pull().head, ref: replacement, sha: "b".repeat(40) } });
  const result = planReviewedPullRequest({
    pulls: [[closed, otherBase]],
    refs: { [`${replacement}-2`]: "c".repeat(40) },
    repository,
    branch,
    expected,
  });
  assert.equal(result.branch, `${replacement}-3`);
  assert.equal(result.action, "create");
});

test("ignores fork PRs but rejects every occupied official candidate", () => {
  const fork = pull({ head: { ...pull().head, repo: { full_name: "someone/fork" } } });
  assert.equal(planReviewedPullRequest({ pulls: [[fork]], refs: {}, repository, branch, expected }).action, "create");
  const collisions = Array.from({ length: 10 }, (_, index) => pull({
    head: { ...pull().head, ref: `${branch}${index === 0 ? "" : `-${index + 1}`}`, sha: String(index) },
  }));
  assert.throws(() => planReviewedPullRequest({ pulls: [collisions], refs: {}, repository, branch, expected }));
});
