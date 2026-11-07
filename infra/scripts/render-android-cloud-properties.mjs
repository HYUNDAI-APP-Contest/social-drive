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

export function renderAndroidCloudProperties(document) {
  const stacks = document?.Stacks;
  if (!Array.isArray(stacks) || stacks.length !== 1) throw new Error("Expected exactly one CloudFormation stack");
  const outputs = stacks[0]?.Outputs;
  if (!Array.isArray(outputs)) throw new Error("CloudFormation stack outputs are missing");
  if (outputs.some(({ OutputKey }) => /secret|password|credential|token/i.test(String(OutputKey)))) {
    throw new Error("Secret-like CloudFormation output is not allowed");
  }
  const values = new Map(outputs.map(({ OutputKey, OutputValue }) => [OutputKey, OutputValue]));
  return mappings.map(([outputKey, property]) => {
    const value = values.get(outputKey);
    if (typeof value !== "string" || value.length === 0 || value.length > 2048 || /[\r\n\0]/.test(value)) {
      throw new Error(`Missing or invalid non-secret output: ${outputKey}`);
    }
    return `${property}=${value}`;
  }).join("\n") + "\n";
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
