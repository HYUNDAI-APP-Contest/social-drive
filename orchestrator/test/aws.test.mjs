import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { AwsDeployer, formatChangeSummary, summarizeChanges, validateTemplateArtifacts } from "../src/aws.mjs";

const deployer = (runner) => new AwsDeployer({
  awsCommand: "aws",
  awsProfile: "test",
  awsRegion: "ap-northeast-2",
  awsStackName: "social-drive-dev",
}, { runner });

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

test("treats a review-only placeholder as a new stack", async () => {
  const subject = deployer(async () => ({
    stdout: JSON.stringify({ Stacks: [{ StackStatus: "REVIEW_IN_PROGRESS" }] }),
  }));

  assert.equal(await subject.stackExists(), false);
});

test("treats a deployed CloudFormation stack as existing", async () => {
  const subject = deployer(async () => ({
    stdout: JSON.stringify({ Stacks: [{ StackStatus: "UPDATE_COMPLETE" }] }),
  }));

  assert.equal(await subject.stackExists(), true);
});

test("treats a missing CloudFormation stack as new", async () => {
  const subject = deployer(async () => {
    throw new Error("Stack with id social-drive-dev does not exist");
  });

  assert.equal(await subject.stackExists(), false);
});
