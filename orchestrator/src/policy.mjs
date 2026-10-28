const dangerous = /(^|\s)(sudo|su|rm\s+-rf|mkfs|dd\s+if=|shutdown|reboot|launchctl|systemctl|terraform\s+(apply|destroy)|kubectl\s+(apply|delete)|aws\s+.*(delete|terminate|put-|create-)|gh\s+pr\s+merge)(\s|$)/i;

export function classifyCommand(command, workspace) {
  if (!command) return { decision: "decline", reason: "missing command" };
  if (dangerous.test(command)) return { decision: "decline", reason: "high-risk command must be performed outside the worker" };
  if (command.includes("../") || (command.includes("/") && !command.includes(workspace))) {
    return { decision: "decline", reason: "command may escape the issue workspace" };
  }
  return { decision: "accept", reason: "workspace-local development command" };
}

export function budgetStatus(config, usage = { tokens: 0, estimatedUsd: 0 }) {
  const estimatedUsd = usage.estimatedUsd ?? usage.tokens / 1_000_000 * config.estimatedUsdPerMillionTokens;
  if (usage.tokens >= config.dailyTokenLimit) return { allowed: false, reason: "daily token limit reached" };
  if (estimatedUsd >= config.dailyCostLimitUsd) return { allowed: false, reason: "daily estimated cost limit reached" };
  return { allowed: true, estimatedUsd };
}

