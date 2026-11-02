import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const read = (relative) => fs.readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

test("setup never stages or pushes application code", () => {
  const source = read("../../scripts/setup-orchestrator.sh");
  assert.doesNotMatch(source, /git\s+push\s+origin\s+main/);
  assert.doesNotMatch(source, /git\s+add/);
});

test("activation uses an explicit file allowlist and an open PR handoff", () => {
  const source = read("../../scripts/apply-and-test.sh");
  assert.match(source, /control_center_files=\(/);
  assert.match(source, /git add -- "\$\{control_center_files\[@\]\}"/);
  assert.match(source, /"orchestrator\/src\/follow-up\.mjs"/);
  assert.match(source, /"orchestrator\/test\/follow-up\.test\.mjs"/);
  assert.match(source, /--name-only -z/);
  assert.match(source, /read -r -d '' staged/);
  assert.match(source, /git diff --name-only -z/);
  assert.match(source, /커밋되지 않은 tracked 변경/);
  assert.doesNotMatch(source, /git add --[^\n]*\borchestrator\b(?:\s|$)/);
  assert.doesNotMatch(source, /git\s+push\s+origin\s+main/);
  assert.match(source, /--state open/);
  assert.match(source, /--json state --jq \.state/);
  assert.match(source, /== "OPEN"/);
});

test("reviewed work publishing is pinned and never merges or pushes main", () => {
  const source = read("../../scripts/publish-reviewed-work.sh");
  assert.match(source, /agent\/issue-conflict-review\|4f2b559116fd98ee3fe05e05fc6431b59888d0d6/);
  assert.doesNotMatch(source, /agent\/issue-android-cloud-integration\|0d315763353a54c65352fffab2d6cb7222ec4a83/);
  assert.doesNotMatch(source, /agent\/issue-route-consent\|b1c33a22b18c62806ee89337ef04635c53dd302a/);
  assert.match(source, /agent\/issue-backend-mvp\|ddbd61fde381d09bc4605c758126ebf6ba0a5ea5/);
  assert.match(source, /agent\/issue-contest-submission\|6bfc486030bbc646cd1f449fe9c7ed4d5518db7c/);
  assert.match(source, /status --porcelain/);
  assert.match(source, /rev-parse HEAD/);
  assert.match(source, /permissions\.push/);
  assert.match(source, /--paginate --slurp/);
  assert.match(source, /reviewed-pr-plan\.mjs/);
  assert.match(source, /trap cleanup_snapshot EXIT/);
  assert.match(source, /trap 'exit 130' INT/);
  assert.match(source, /trap 'exit 143' TERM/);
  assert.match(source, /\$\{expected\}:refs\/heads\/\$\{publish_branch\}/);
  assert.match(source, /gh pr create/);
  assert.doesNotMatch(source, /gh\s+pr\s+merge/);
  assert.doesNotMatch(source, /git\s+push[^\n]*\bmain\b/);
});

test("termination exits before later mutations and still runs EXIT cleanup", () => {
  const result = spawnSync("bash", ["-c", "trap 'printf cleaned' EXIT; trap 'exit 143' TERM; kill -TERM $$; printf continued"]);
  assert.equal(result.status, 143);
  assert.equal(result.stdout.toString(), "cleaned");
});

test("status fails closed unless launchd reports a running pid", () => {
  const source = read("../../scripts/orchestrator-status.sh");
  assert.match(source, /state = running/);
  assert.match(source, /pid = \[1-9\]\[0-9\]\*/);
  assert.match(source, /social-drive-orchestrator-v1/);
  assert.match(source, /dashboard heartbeat/);
  assert.match(source, /dashboardIntervalMs/);
  assert.match(source, /DASHBOARD_PATH|dashboardPath/);
  assert.match(source, /exit 1/);
  assert.doesNotMatch(source, /launchctl print[^\n]*\|\| true/);
});
