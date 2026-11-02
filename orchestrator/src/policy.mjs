const dangerous = /(^|\s)(sudo|su|rm\s+-rf|mkfs|dd\s+if=|shutdown|reboot|launchctl|systemctl|terraform\s+(apply|destroy)|kubectl\s+(apply|delete)|aws\s+.*(delete|terminate|put-|create-)|gh\s+pr\s+merge)(\s|$)/i;

export function classifyCommand(command, workspace) {
  if (!command) return { decision: "decline", reason: "missing command" };
  if (dangerous.test(command)) return { decision: "decline", reason: "high-risk command must be performed outside the worker" };
  if (command.includes("../") || (command.includes("/") && !command.includes(workspace))) {
    return { decision: "decline", reason: "command may escape the issue workspace" };
  }
  return { decision: "accept", reason: "workspace-local development command" };
}

export function budgetStatus(config, usage = { tokens: 0, estimatedUsd: 0 }, accountUsage = null, now = Date.now()) {
  if (accountUsage && Number.isFinite(Number(accountUsage.usedPercent))) {
    const updatedAt = Date.parse(accountUsage.updatedAt ?? "");
    const resetsAtMs = Number(accountUsage.resetsAt ?? 0) * 1000;
    if (!Number.isFinite(updatedAt) || now - updatedAt > config.accountUsageMaxAgeMs) return { allowed: false, reason: "Codex account usage snapshot is stale" };
    if (!resetsAtMs || resetsAtMs <= now) return { allowed: false, reason: "Codex account usage window expired" };
    if (accountUsage.ordinaryUsageAllowed === false) return { allowed: false, reason: "Codex account usage is not allowed" };
    if (Number(accountUsage.usedPercent) >= config.accountUsagePausePercent) return { allowed: false, reason: "Codex account usage safety threshold reached" };
    return { allowed: true, usedPercent: Number(accountUsage.usedPercent) };
  }
  return { allowed: false, reason: "Codex account usage snapshot is unavailable" };
}

export function isUsageLimitError(error) {
  const info = typeof error?.codexErrorInfo === "string" ? error.codexErrorInfo : JSON.stringify(error?.codexErrorInfo ?? "");
  const message = `${error?.message ?? ""} ${info}`;
  return /usageLimitExceeded|rateLimitExceeded|sessionBudgetExceeded|usage limit|rate limit/i.test(message);
}

