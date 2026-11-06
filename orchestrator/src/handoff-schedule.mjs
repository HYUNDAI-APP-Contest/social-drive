const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

export function prHandoffSchedule(now = new Date(), notBeforeHourKst = 8) {
  const shifted = new Date(now.getTime() + KST_OFFSET_MS);
  const hour = shifted.getUTCHours();
  if (hour >= notBeforeHourKst) return { open: true, nextAt: null };
  const nextAtMs = Date.UTC(
    shifted.getUTCFullYear(),
    shifted.getUTCMonth(),
    shifted.getUTCDate(),
    notBeforeHourKst,
  ) - KST_OFFSET_MS;
  return { open: false, nextAt: new Date(nextAtMs).toISOString() };
}

export function recordHandoffFailure(state, key, message, now = new Date()) {
  const job = state.jobs?.[key];
  if (!job) return "missing";
  const approval = job.pr ? state.approvals?.[`pr-${job.pr}`] : null;
  if (job.status === "awaiting_merge_approval" && approval?.status === "pending") return "preserved";
  job.status = "handoff_deferred";
  job.error = String(message);
  job.updatedAt = now.toISOString();
  return "deferred";
}
