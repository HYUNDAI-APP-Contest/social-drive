import fs from "node:fs/promises";
import path from "node:path";
import { run } from "./process.mjs";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const json = (value) => JSON.parse(value || "{}");
const inside = (parent, child) => child === parent || child.startsWith(`${parent}${path.sep}`);

export async function validateTemplateArtifacts(templatePath, infrastructureRoot) {
  const template = await fs.readFile(templatePath, "utf8");
  const root = await fs.realpath(infrastructureRoot);
  const entries = [];
  for (const line of template.split("\n")) {
    const match = line.match(/^\s*(CodeUri|ContentUri|DefinitionUri):\s*([^#]+?)\s*$/);
    if (!match) continue;
    const raw = match[2].replace(/^['"]|['"]$/g, "").trim();
    if (!raw || raw.startsWith("!") || raw.startsWith("{") || /^[a-z]+:\/\//i.test(raw) || raw.startsWith("s3:")) continue;
    if (path.isAbsolute(raw)) throw new Error(`${match[1]} must be relative to infra/: ${raw}`);
    const candidate = await fs.realpath(path.resolve(infrastructureRoot, raw)).catch(() => null);
    if (!candidate) throw new Error(`${match[1]} does not exist: ${raw}`);
    if (!inside(root, candidate)) throw new Error(`${match[1]} escapes infra/: ${raw}`);
    entries.push({ type: match[1], path: raw });
  }
  return entries;
}

export function summarizeChanges(description) {
  return (description.Changes ?? []).slice(0, 20).map(({ ResourceChange: change }) => ({
    action: change.Action,
    logicalId: change.LogicalResourceId,
    resourceType: change.ResourceType,
    replacement: change.Replacement ?? "False",
    highRisk: change.Action === "Remove" || ["True", "Conditional"].includes(change.Replacement),
  }));
}

export function formatChangeSummary(changes) {
  if (!changes.length) return "리소스 변경 없음";
  return changes.map((change) => {
    const warning = change.highRisk ? " ⚠️" : "";
    const replacement = change.replacement !== "False" ? ` · 교체 ${change.replacement}` : "";
    return `• **${change.action}** \`${change.logicalId}\` (${change.resourceType}${replacement})${warning}`;
  }).join("\n");
}

const terminalFailure = (status) => /FAILED|ROLLBACK|DELETE_COMPLETE|UPDATE_ROLLBACK/.test(status);
const noChanges = (reason = "") => /didn['’]?t contain changes|no updates are to be performed|no changes/i.test(reason);

export class AwsDeployer {
  constructor(config, { runner = run, fetcher = fetch, sleeper = sleep } = {}) {
    this.config = config; this.runner = runner; this.fetcher = fetcher; this.sleeper = sleeper;
  }

  async aws(args, options = {}) {
    const fullArgs = [...args, "--profile", this.config.awsProfile, "--region", this.config.awsRegion, "--no-cli-pager"];
    return this.runner(this.config.awsCommand, fullArgs, { ...options, timeoutMs: options.timeoutMs ?? 120_000 });
  }

  async deploymentIdentity() {
    const identity = json((await this.aws(["sts", "get-caller-identity", "--output", "json"])).stdout);
    if (!/^\d{12}$/.test(String(identity.Account ?? ""))) throw new Error("AWS deploy profile returned an invalid account");
    const accountId = String(identity.Account);
    return {
      bucket: this.config.awsArtifactBucket || `social-drive-sam-artifacts-${accountId}-${this.config.awsRegion}`,
      roleArn: this.config.awsCloudFormationRoleArn || `arn:aws:iam::${accountId}:role/SocialDriveCloudFormationServiceRole`,
    };
  }

  async stackExists() {
    try {
      const description = json((await this.aws([
        "cloudformation", "describe-stacks", "--stack-name", this.config.awsStackName, "--output", "json",
      ])).stdout);
      const status = description.Stacks?.[0]?.StackStatus;
      // CloudFormation creates a REVIEW_IN_PROGRESS placeholder for a CREATE change set.
      // A failed or abandoned change set can leave that placeholder visible to
      // describe-stacks even though there is no deployed stack to update. The next
      // candidate must therefore be another CREATE change set.
      return Boolean(status && status !== "REVIEW_IN_PROGRESS");
    } catch (error) {
      if (/does not exist/i.test(error.message)) return false;
      throw error;
    }
  }

  async describeChangeSet(changeSetArn) {
    return json((await this.aws(["cloudformation", "describe-change-set", "--change-set-name", changeSetArn, "--output", "json"])).stdout);
  }

  async prepare({ workspace, sourceSha, pr = null, issue = null, requestedBy = null }) {
    const template = path.join(workspace, "infra", "template.yaml");
    await validateTemplateArtifacts(template, path.join(workspace, "infra"));
    const { bucket, roleArn } = await this.deploymentIdentity();
    await this.aws(["s3api", "head-bucket", "--bucket", bucket]);

    const generated = path.join(workspace, ".orchestrator");
    const packaged = path.join(generated, "packaged.yaml");
    await fs.mkdir(generated, { recursive: true });
    const functionRoot = path.join(workspace, "infra", "functions", "api");
    await fs.access(path.join(functionRoot, "package-lock.json"));
    await this.runner("npm", ["ci", "--omit=dev", "--ignore-scripts"], { cwd: functionRoot, timeoutMs: 10 * 60_000 });
    await this.aws([
      "cloudformation", "package", "--template-file", template, "--s3-bucket", bucket,
      "--s3-prefix", `deployments/${sourceSha}`, "--output-template-file", packaged,
    ], { cwd: workspace, timeoutMs: 10 * 60_000 });

    const changeSetType = await this.stackExists() ? "UPDATE" : "CREATE";
    const suffix = Date.now().toString(36);
    const source = pr ? `pr-${pr}` : "manual";
    const changeSetName = `social-drive-${source}-${suffix}`.slice(0, 128);
    const creation = json((await this.aws([
      "cloudformation", "create-change-set",
      "--stack-name", this.config.awsStackName,
      "--change-set-name", changeSetName,
      "--change-set-type", changeSetType,
      "--template-body", `file://${packaged}`,
      "--capabilities", "CAPABILITY_IAM",
      "--role-arn", roleArn,
      "--parameters", `ParameterKey=Environment,ParameterValue=${this.config.awsEnvironment}`,
      "--tags", "Key=Project,Value=social-drive", `Key=Environment,Value=${this.config.awsEnvironment}`, `Key=SourceCommit,Value=${sourceSha.slice(0, 40)}`,
      "--description", `Approved deployment candidate for ${sourceSha.slice(0, 40)}`,
      "--output", "json",
    ], { cwd: workspace })).stdout);
    const changeSetArn = creation.Id;
    if (!changeSetArn) throw new Error("AWS did not return a change set ID");

    try {
      await this.aws(["cloudformation", "wait", "change-set-create-complete", "--change-set-name", changeSetArn], { timeoutMs: 15 * 60_000 });
    } catch (waitError) {
      const failed = await this.describeChangeSet(changeSetArn).catch(() => null);
      if (!failed || !noChanges(failed.StatusReason)) throw waitError;
      return { noChanges: true, changeSetArn, changeSetName, sourceSha, workspace };
    }
    const description = await this.describeChangeSet(changeSetArn);
    const changes = summarizeChanges(description);
    return {
      id: `aws-${suffix}`,
      type: "aws-deploy",
      changeSetArn,
      changeSetName,
      changeSetType,
      stackName: this.config.awsStackName,
      sourceSha,
      workspace,
      pr,
      issue,
      requestedBy,
      changes,
      highRisk: changes.some((change) => change.highRisk),
    };
  }

  async recentFailure() {
    try {
      const events = json((await this.aws([
        "cloudformation", "describe-stack-events", "--stack-name", this.config.awsStackName,
        "--max-items", "10", "--output", "json",
      ])).stdout).StackEvents ?? [];
      const failed = events.find((event) => /FAILED|ROLLBACK/.test(event.ResourceStatus ?? ""));
      return failed ? `${failed.LogicalResourceId}: ${failed.ResourceStatusReason ?? failed.ResourceStatus}` : "CloudFormation stack failed";
    } catch { return "CloudFormation stack failed"; }
  }

  async execute(approval, onStatus = () => {}) {
    const description = await this.describeChangeSet(approval.changeSetArn);
    if (description.StackName !== this.config.awsStackName) throw new Error("Change set stack does not match the configured dev stack");
    if (description.Status !== "CREATE_COMPLETE" || description.ExecutionStatus !== "AVAILABLE") throw new Error(`Change set is not executable: ${description.Status}/${description.ExecutionStatus}`);
    await this.aws(["cloudformation", "execute-change-set", "--change-set-name", approval.changeSetArn]);
    await onStatus("CloudFormation이 승인된 변경 세트를 실행 중입니다.");

    return this.waitForCompletion();
  }

  async waitForCompletion() {
    const deadline = Date.now() + this.config.awsDeploymentTimeoutMs;
    let stack;
    while (Date.now() < deadline) {
      stack = json((await this.aws(["cloudformation", "describe-stacks", "--stack-name", this.config.awsStackName, "--output", "json"])).stdout).Stacks?.[0];
      const status = stack?.StackStatus ?? "UNKNOWN";
      if (["CREATE_COMPLETE", "UPDATE_COMPLETE"].includes(status)) break;
      if (terminalFailure(status)) throw new Error(await this.recentFailure());
      await this.sleeper(10_000);
    }
    if (!stack || !["CREATE_COMPLETE", "UPDATE_COMPLETE"].includes(stack.StackStatus)) throw new Error("AWS deployment timed out");

    const outputs = Object.fromEntries((stack.Outputs ?? []).map((item) => [item.OutputKey, item.OutputValue]));
    let smoke = { attempted: false, passed: false, status: null };
    if (outputs.ApiUrl) {
      const response = await this.fetcher(`${String(outputs.ApiUrl).replace(/\/$/, "")}/feed`, { signal: AbortSignal.timeout(15_000), redirect: "error" });
      smoke = { attempted: true, passed: response.status === 401 || response.status === 403, status: response.status };
      if (!smoke.passed) throw new Error(`API authorization smoke test returned HTTP ${response.status}`);
    }
    return { stackStatus: stack.StackStatus, outputKeys: Object.keys(outputs), smoke };
  }

  async recover(approval) {
    const description = await this.describeChangeSet(approval.changeSetArn);
    if (description.ExecutionStatus === "AVAILABLE") return { action: "pending" };
    return { action: "complete", result: await this.waitForCompletion() };
  }

  async reject(approval) {
    await this.aws(["cloudformation", "delete-change-set", "--change-set-name", approval.changeSetArn]);
  }
}
