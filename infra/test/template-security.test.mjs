import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const template = fs.readFileSync(new URL("../template.yaml", import.meta.url), "utf8");

test("keeps storage private, encrypted, on-demand, and retained", () => {
  for (const expected of ["BlockPublicAcls: true", "RestrictPublicBuckets: true", "SSEEnabled: true", "BillingMode: PAY_PER_REQUEST", "PointInTimeRecoveryEnabled: true", "TimeToLiveSpecification:", "AttributeName: expiresAtEpoch", "DeletionPolicy: Retain"]) assert.match(template, new RegExp(expected));
});

test("exposes the complete protected MVP API with least privilege operations", () => {
  for (const route of ["POST /drives", "/drives/{driveId}/uploads", "/drives/{driveId}/publish", "GET /drives/{driveId}", "PATCH", "DELETE"]) {
    const [method, path] = route.includes(" ") ? route.split(" ") : [null, route];
    if (method) { assert.ok(template.includes(`Method: ${method}`)); assert.ok(template.includes(`Path: ${path}`)); }
    else assert.ok(template.includes(route));
  }
  for (const action of ["dynamodb:TransactWriteItems", "s3:DeleteObject"]) assert.ok(template.includes(action));
  assert.match(template, /UPLOAD_URL_TTL_SECONDS: '600'/);
});

test("protects API routes with Cognito JWT", () => {
  for (const expected of ["DefaultAuthorizer: CognitoJwt", "$request.header.Authorization", "GenerateSecret: false"]) assert.ok(template.includes(expected));
});

test("contains no account id or credentials", () => {
  assert.doesNotMatch(template, /\b\d{12}\b|AKIA[0-9A-Z]{16}|aws_secret_access_key/i);
});

test("exports every non-secret Android cloud build input", () => {
  for (const output of ["CloudRegion:", "ApiUrl:", "UserPoolId:", "UserPoolClientId:", "UploadHost:", "ConsentPolicyVersion:"]) {
    assert.ok(template.includes(output), `missing ${output}`);
  }
  assert.match(template, /Value: !Sub \$\{MediaBucket\}\.s3\.\$\{AWS::Region\}\.\$\{AWS::URLSuffix\}/);
  assert.match(template, /AllowedPattern: '\^\[A-Za-z0-9\._-\]\{1,32\}\$'/);
});

