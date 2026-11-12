import test from "node:test";
import assert from "node:assert/strict";
import { renderAndroidCloudProperties } from "../scripts/render-android-cloud-properties.mjs";

const outputs = {
  Stacks: [{
    Outputs: [
      { OutputKey: "ApiUrl", OutputValue: "https://api.example.com/dev" },
      { OutputKey: "UploadHost", OutputValue: "uploads.example.com" },
      { OutputKey: "ConsentPolicyVersion", OutputValue: "2026-11-07.v1" },
      { OutputKey: "CloudRegion", OutputValue: "ap-northeast-2" },
      { OutputKey: "UserPoolId", OutputValue: "ap-northeast-2_Abc123XYZ" },
      { OutputKey: "UserPoolClientId", OutputValue: "abcdefghijklmnopqrstuvwxyz" },
      { OutputKey: "MediaBucketName", OutputValue: "not-rendered" },
    ],
  }],
};

test("converts approved stack outputs to the six Android public properties", () => {
  const rendered = renderAndroidCloudProperties(outputs);
  assert.deepEqual(rendered.trim().split("\n"), [
    "socialDrive.cloud.apiBaseUrl=https://api.example.com/dev",
    "socialDrive.cloud.uploadHosts=uploads.example.com",
    "socialDrive.cloud.consentPolicyVersion=2026-11-07.v1",
    "socialDrive.cloud.cognitoRegion=ap-northeast-2",
    "socialDrive.cloud.cognitoUserPoolId=ap-northeast-2_Abc123XYZ",
    "socialDrive.cloud.cognitoClientId=abcdefghijklmnopqrstuvwxyz",
  ]);
  assert.doesNotMatch(rendered, /MediaBucketName|not-rendered/);
});

test("rejects incomplete, multiline, and secret-like stack outputs", () => {
  assert.throws(() => renderAndroidCloudProperties({ Stacks: [{ Outputs: [] }] }), /ApiUrl/);
  const multiline = structuredClone(outputs);
  multiline.Stacks[0].Outputs[0].OutputValue += "\nunsafe";
  assert.throws(() => renderAndroidCloudProperties(multiline), /ApiUrl/);
  const secret = structuredClone(outputs);
  secret.Stacks[0].Outputs.push({ OutputKey: "ClientSecret", OutputValue: "must-not-exist" });
  assert.throws(() => renderAndroidCloudProperties(secret), /Secret-like/);
});

test("rejects malformed or internally inconsistent public cloud outputs", () => {
  for (const [outputKey, outputValue, expected] of [
    ["ApiUrl", "http://api.example.com/dev", /ApiUrl/],
    ["ApiUrl", " https://api.example.com/dev", /ApiUrl/],
    ["UploadHost", "https://uploads.example.com/path", /UploadHost/],
    ["ConsentPolicyVersion", "policy version", /ConsentPolicyVersion/],
    ["ConsentPolicyVersion", "2026-11-07", /ConsentPolicyVersion/],
    ["CloudRegion", "ap-northeast-1", /Cognito/],
    ["UserPoolId", "ap-northeast-2_too-short", /Cognito/],
    ["UserPoolClientId", "too-short", /Cognito/],
  ]) {
    const invalid = structuredClone(outputs);
    invalid.Stacks[0].Outputs.find(({ OutputKey }) => OutputKey === outputKey).OutputValue = outputValue;
    assert.throws(() => renderAndroidCloudProperties(invalid), expected);
  }

  const duplicate = structuredClone(outputs);
  duplicate.Stacks[0].Outputs.push({ OutputKey: "ApiUrl", OutputValue: "https://other.example.com" });
  assert.throws(() => renderAndroidCloudProperties(duplicate), /Duplicate/);
});
