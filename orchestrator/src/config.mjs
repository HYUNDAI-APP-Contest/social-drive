import path from "node:path";

const integer = (name, fallback, minimum = 0) => {
  const value = Number.parseInt(process.env[name] ?? String(fallback), 10);
  if (!Number.isFinite(value) || value < minimum) throw new Error(`${name} must be >= ${minimum}`);
  return value;
};

const number = (name, fallback, minimum = 0) => {
  const value = Number.parseFloat(process.env[name] ?? String(fallback));
  if (!Number.isFinite(value) || value < minimum) throw new Error(`${name} must be >= ${minimum}`);
  return value;
};

const boolean = (name, fallback) => {
  const value = (process.env[name] ?? String(fallback)).toLowerCase();
  if (!["true", "false"].includes(value)) throw new Error(`${name} must be true or false`);
  return value === "true";
};

export function loadConfig({ requireSecrets = true } = {}) {
  const config = {
    githubOwner: process.env.GITHUB_OWNER ?? "HYUNDAI-APP-Contest",
    githubRepo: process.env.GITHUB_REPO ?? "social-drive",
    githubToken: process.env.GITHUB_TOKEN ?? "",
    readyLabel: process.env.GITHUB_READY_LABEL ?? "agent:ready",
    baseBranch: process.env.GITHUB_BASE_BRANCH ?? "main",
    githubProjectId: process.env.GITHUB_PROJECT_ID ?? "",
    githubProjectUrl: process.env.GITHUB_PROJECT_URL ?? "",
    discordWebhookUrl: process.env.DISCORD_WEBHOOK_URL ?? "",
    discordBotToken: process.env.DISCORD_BOT_TOKEN ?? "",
    discordChannelId: process.env.DISCORD_CHANNEL_ID ?? "",
    discordGuildId: process.env.DISCORD_GUILD_ID ?? "",
    discordApplicationId: process.env.DISCORD_APPLICATION_ID ?? "",
    discordApprovers: new Set((process.env.DISCORD_APPROVER_USER_IDS ?? "").split(",").map((v) => v.trim()).filter(Boolean)),
    discordBootstrapCode: process.env.DISCORD_BOOTSTRAP_CODE ?? "",
    discordDisplayName: process.env.DISCORD_DISPLAY_NAME ?? "개발자",
    discordAvatarPath: path.resolve(process.env.DISCORD_AVATAR_PATH ?? "./orchestrator/assets/developer-avatar.png"),
    discordArchitectAvatarPath: path.resolve(process.env.DISCORD_ARCHITECT_AVATAR_PATH ?? "./orchestrator/assets/personas/aki-architect.png"),
    discordDesignerAvatarPath: path.resolve(process.env.DISCORD_DESIGNER_AVATAR_PATH ?? "./orchestrator/assets/personas/pixie-designer.png"),
    discordReviewerAvatarPath: path.resolve(process.env.DISCORD_REVIEWER_AVATAR_PATH ?? "./orchestrator/assets/personas/sentinel-reviewer.png"),
    codexCommand: process.env.CODEX_COMMAND ?? "codex app-server --listen stdio://",
    codexModel: process.env.CODEX_MODEL || null,
    maxParallel: integer("MAX_PARALLEL_AGENTS", 3, 1),
    maxReviewRounds: integer("MAX_REVIEW_ROUNDS", 2, 1),
    pollIntervalMs: integer("POLL_INTERVAL_SECONDS", 30, 5) * 1000,
    turnTimeoutMs: integer("TURN_TIMEOUT_MINUTES", 90, 1) * 60_000,
    maxRetries: integer("MAX_RETRIES", 3, 0),
    usageLimitCooldownMs: integer("USAGE_LIMIT_COOLDOWN_MINUTES", 60, 5) * 60_000,
    dailyTokenLimit: integer("DAILY_TOKEN_LIMIT", 2_000_000, 1),
    dailyCostLimitUsd: number("DAILY_COST_LIMIT_USD", 25, 0),
    estimatedUsdPerMillionTokens: number("ESTIMATED_USD_PER_1M_TOKENS", 10, 0),
    approvalTtlMs: integer("APPROVAL_TTL_MINUTES", 720, 1) * 60_000,
    sourceRepository: path.resolve(process.env.SOURCE_REPOSITORY ?? "."),
    workspaceRoot: path.resolve(process.env.WORKSPACE_ROOT ?? "./orchestrator/workspaces"),
    statePath: path.resolve(process.env.STATE_PATH ?? "./orchestrator/data/state.json"),
    dashboardPath: path.resolve(process.env.DASHBOARD_PATH ?? "./status-dashboard.html"),
    dashboardIntervalMs: integer("DASHBOARD_INTERVAL_SECONDS", 10, 1) * 1000,
    outboxIntervalMs: integer("OUTBOX_INTERVAL_SECONDS", 10, 1) * 1000,
    outboxMaxAttempts: integer("OUTBOX_MAX_ATTEMPTS", 8, 1),
    deploymentRoot: path.resolve(process.env.DEPLOYMENT_ROOT ?? "./orchestrator/deployments"),
    awsDeployEnabled: boolean("AWS_DEPLOY_ENABLED", true),
    awsCommand: process.env.AWS_COMMAND ?? (process.platform === "darwin" ? "/usr/local/bin/aws" : "aws"),
    awsProfile: process.env.AWS_PROFILE ?? "social-drive-deployer",
    awsRegion: process.env.AWS_REGION ?? "ap-northeast-2",
    awsStackName: process.env.AWS_STACK_NAME ?? "social-drive-dev",
    awsEnvironment: process.env.AWS_ENVIRONMENT ?? "dev",
    awsArtifactBucket: process.env.AWS_ARTIFACT_BUCKET ?? "",
    awsCloudFormationRoleArn: process.env.AWS_CLOUDFORMATION_ROLE_ARN ?? "",
    awsDeploymentTimeoutMs: integer("AWS_DEPLOYMENT_TIMEOUT_MINUTES", 30, 5) * 60_000,
  };

  if (requireSecrets) {
    for (const [name, value] of [["GITHUB_TOKEN", config.githubToken], ["DISCORD_WEBHOOK_URL", config.discordWebhookUrl], ["DISCORD_BOT_TOKEN", config.discordBotToken], ["DISCORD_CHANNEL_ID", config.discordChannelId], ["DISCORD_GUILD_ID", config.discordGuildId], ["DISCORD_APPLICATION_ID", config.discordApplicationId]]) {
      if (!value) throw new Error(`${name} is required`);
    }
    if (config.discordApprovers.size === 0 && !config.discordBootstrapCode) throw new Error("DISCORD_APPROVER_USER_IDS or DISCORD_BOOTSTRAP_CODE is required");
  }
  return config;
}

