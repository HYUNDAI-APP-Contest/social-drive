const terminalStatuses = new Set([
  "awaiting_merge_approval", "awaiting_deploy_approval", "merged", "deployed",
  "rejected", "deploy_rejected", "failed", "merge_failed", "deploy_failed",
  "merge_approval_expired", "deploy_approval_expired",
]);

export async function reconcileReadyIssues({
  issues,
  store,
  activeKeys,
  maxParallel,
  enqueueSnapshot,
  now = new Date(),
}) {
  const sourceKeys = new Set(issues.map((issue) => String(issue.number)));
  await store.update((state) => {
    for (const [key, job] of Object.entries(state.jobs)) {
      if (job.status !== "ready" || sourceKeys.has(key) || activeKeys.has(key)) continue;
      job.status = "removed_from_queue";
      job.removedFromQueueAt = now.toISOString();
      job.nextRetryAt = null;
      job.updatedAt = now.toISOString();
      enqueueSnapshot(state, key, now);
    }
    for (const issue of issues) {
      const key = String(issue.number);
      const existing = state.jobs[key];
      if (!existing) {
        state.jobs[key] = {
          issue: issue.number,
          issueNodeId: issue.node_id,
          title: issue.title,
          status: "ready",
          attempts: 0,
          tokens: 0,
          updatedAt: now.toISOString(),
        };
        enqueueSnapshot(state, key, now);
      } else if (existing.status === "removed_from_queue") {
        existing.status = "ready";
        existing.title = issue.title;
        existing.issueNodeId = issue.node_id;
        existing.updatedAt = now.toISOString();
        delete existing.removedFromQueueAt;
        enqueueSnapshot(state, key, now);
      } else if (existing.status === "ready") {
        if (existing.title !== issue.title || existing.issueNodeId !== issue.node_id) {
          existing.title = issue.title;
          existing.issueNodeId = issue.node_id;
          existing.updatedAt = now.toISOString();
          enqueueSnapshot(state, key, now);
        }
      }
    }
  });

  const available = Math.max(0, maxParallel - activeKeys.size);
  return issues.filter((issue) => {
    const key = String(issue.number);
    const job = store.state.jobs[key];
    if (activeKeys.has(key) || terminalStatuses.has(job?.status)) return false;
    if (job?.nextRetryAt && now.getTime() < job.nextRetryAt) return false;
    return ["ready", "retry", "paused_usage_limit"].includes(job?.status);
  }).slice(0, available);
}
