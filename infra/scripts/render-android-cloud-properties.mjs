import fs from "node:fs";
import { pathToFileURL } from "node:url";

const mappings = Object.freeze([
  ["ApiUrl", "socialDrive.cloud.apiBaseUrl"],
  ["UploadHost", "socialDrive.cloud.uploadHosts"],
  ["ConsentPolicyVersion", "socialDrive.cloud.consentPolicyVersion"],
  ["CloudRegion", "socialDrive.cloud.cognitoRegion"],
  ["UserPoolId", "socialDrive.cloud.cognitoUserPoolId"],
  ["UserPoolClientId", "socialDrive.cloud.cognitoClientId"],
]);

const regionPattern = /^[a-z]{2}(?:-gov)?-[a-z]+-\d$/;
const approvedConsentPolicyVersion = "2026-11-07.v1";

function isValidCloudHost(value) {
  if (typeof value !== "string" || value.length < 4 || value.length > 253 ||
      value.startsWith(".") || value.endsWith(".")) return false;
  const labels = value.split(".");
  if (labels.length < 2 || !/[a-z]/.test(labels.at(-1))) return false;
  return labels.every((label) => label.length >= 1 && label.length <= 63 &&
    /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label));
}

function validatePublicConfiguration(values) {
  const apiValue = values.get("ApiUrl");
  if (apiValue !== apiValue.trim() || apiValue.includes("\\")) {
    throw new Error("Invalid public output: ApiUrl");
  }
  let api;
  try {
    api = new URL(apiValue);
  } catch {
    throw new Error("Invalid public output: ApiUrl");
  }
  if (api.protocol !== "https:" || api.username || api.password || api.search || api.hash ||
      (api.port && api.port !== "443") || !isValidCloudHost(api.hostname.toLowerCase())) {
    throw new Error("Invalid public output: ApiUrl");
  }

  const uploadHosts = values.get("UploadHost").split(",").map((value) => value.trim()).filter(Boolean);
  if (uploadHosts.length === 0 || uploadHosts.some((host) => host !== host.toLowerCase() || !isValidCloudHost(host))) {
    throw new Error("Invalid public output: UploadHost");
  }
  if (values.get("ConsentPolicyVersion") !== approvedConsentPolicyVersion) {
    throw new Error("Invalid public output: ConsentPolicyVersion");
  }

  const region = values.get("CloudRegion").trim().toLowerCase();
  const pool = values.get("UserPoolId").trim();
  const client = values.get("UserPoolClientId").trim().toLowerCase();
  const poolMatch = /^([a-z]{2}(?:-gov)?-[a-z]+-\d)_[A-Za-z0-9]{9}$/.exec(pool);
  if (!regionPattern.test(region) || poolMatch?.[1] !== region || !/^[a-z0-9]{26}$/.test(client)) {
    throw new Error("Invalid public Cognito outputs");
  }
}

export function renderAndroidCloudProperties(document) {
  const stacks = document?.Stacks;
  if (!Array.isArray(stacks) || stacks.length !== 1) throw new Error("Expected exactly one CloudFormation stack");
  const outputs = stacks[0]?.Outputs;
  if (!Array.isArray(outputs)) throw new Error("CloudFormation stack outputs are missing");
  if (outputs.some(({ OutputKey }) => /secret|password|credential|token/i.test(String(OutputKey)))) {
    throw new Error("Secret-like CloudFormation output is not allowed");
  }
  const outputKeys = outputs.map(({ OutputKey }) => OutputKey);
  if (new Set(outputKeys).size !== outputKeys.length) throw new Error("Duplicate CloudFormation output key");
  const values = new Map(outputs.map(({ OutputKey, OutputValue }) => [OutputKey, OutputValue]));
  const rendered = mappings.map(([outputKey, property]) => {
    const value = values.get(outputKey);
    if (typeof value !== "string" || value.length === 0 || value.length > 2048 || /[\r\n\0]/.test(value)) {
      throw new Error(`Missing or invalid non-secret output: ${outputKey}`);
    }
    return `${property}=${value}`;
  });
  validatePublicConfiguration(values);
  return rendered.join("\n") + "\n";
}

function main(argv) {
  if (argv.length !== 1) throw new Error("Usage: render-android-cloud-properties.mjs <describe-stacks.json>");
  const document = JSON.parse(fs.readFileSync(argv[0], "utf8"));
  process.stdout.write(renderAndroidCloudProperties(document));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
