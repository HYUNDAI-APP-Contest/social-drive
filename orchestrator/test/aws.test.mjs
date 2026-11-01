import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { formatChangeSummary, summarizeChanges, validateTemplateArtifacts } from "../src/aws.mjs";

test("summarizes replacement and removal as high risk", () => {
  const changes = summarizeChanges({ Changes: [
    { ResourceChange: { Action: "Modify", LogicalResourceId: "Table", ResourceType: "AWS::DynamoDB::Table", Replacement: "Conditional" } },
    { ResourceChange: { Action: "Remove", LogicalResourceId: "Bucket", ResourceType: "AWS::S3::Bucket", Replacement: "False" } },
  ] });
  assert.equal(changes.every((change) => change.highRisk), true);
  assert.match(formatChangeSummary(changes), /교체 Conditional/);
});

test("accepts local SAM artifacts contained by infra", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-aws-"));
  await fs.mkdir(path.join(root, "functions", "api"), { recursive: true });
  await fs.writeFile(path.join(root, "template.yaml"), "Resources:\n  Fn:\n    Properties:\n      CodeUri: functions/api/\n");
  assert.deepEqual(await validateTemplateArtifacts(path.join(root, "template.yaml"), root), [{ type: "CodeUri", path: "functions/api/" }]);
  await fs.rm(root, { recursive: true, force: true });
});

test("rejects SAM artifacts outside infra", async () => {
  const parent = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-aws-"));
  const root = path.join(parent, "infra");
  await fs.mkdir(root);
  await fs.mkdir(path.join(parent, "secret"));
  await fs.writeFile(path.join(root, "template.yaml"), "CodeUri: ../secret\n");
  await assert.rejects(validateTemplateArtifacts(path.join(root, "template.yaml"), root), /escapes infra/);
  await fs.rm(parent, { recursive: true, force: true });
});
