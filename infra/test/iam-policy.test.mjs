import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const policy = JSON.parse(fs.readFileSync(new URL("../iam/social-drive-cloudformation-service-policy.json", import.meta.url), "utf8"));
const bootstrap = fs.readFileSync(new URL("../bootstrap/bootstrap-aws.sh", import.meta.url), "utf8");

test("allows only the exact Seoul SAM transform required to create a change set", () => {
  const statements = policy.Statement.filter((statement) => {
    const actions = Array.isArray(statement.Action) ? statement.Action : [statement.Action];
    return actions.includes("cloudformation:CreateChangeSet");
  });
  assert.deepEqual(statements, [{
    Sid: "UseExactSamTransform",
    Effect: "Allow",
    Action: "cloudformation:CreateChangeSet",
    Resource: "arn:aws:cloudformation:ap-northeast-2:aws:transform/Serverless-2016-10-31",
  }]);
  assert.match(bootstrap, /"Action": "cloudformation:CreateChangeSet",\n\s+"Resource": "arn:aws:cloudformation:ap-northeast-2:aws:transform\/Serverless-2016-10-31"/);
  assert.doesNotMatch(bootstrap, /"Action": "cloudformation:CreateChangeSet",\n\s+"Resource": "\*"/);
});
