import { enqueueOutbox } from "./outbox.mjs";

// Trusted, reviewed workflow dependencies. Never derive these targets from
// Discord input or free-form issue text.
const FOLLOW_UP_ISSUE_BY_REPOSITORY_AND_PR = new Map([
  ["HYUNDAI-APP-Contest/social-drive:2", 9],
]);

export function followUpIssueForPr(repository, pr) {
  return FOLLOW_UP_ISSUE_BY_REPOSITORY_AND_PR.get(`${repository}:${Number(pr)}`) ?? null;
}

export function bindApprovalFollowUp(approval, repository) {
  const sanitized = { ...approval };
  delete sanitized.followUpIssue;
  const followUpIssue = followUpIssueForPr(repository, approval?.pr);
  return followUpIssue ? { ...sanitized, followUpIssue } : sanitized;
}

export function migrateApprovalFollowUps(state, repository) {
  for (const [id, approval] of Object.entries(state.approvals ?? {})) {
    state.approvals[id] = bindApprovalFollowUp(approval, repository);
  }
}

export function recordMergeResult(state, { approvalId, approval, result, userId, repository, now = Date.now() }) {
  const current = state.approvals?.[approvalId];
  const job = state.jobs?.[String(approval.issue)];
  if (!current || !job) throw new Error("Merge approval state is incomplete");

  current.status = result.merged ? "approved" : "failed";
  current.decidedBy = userId;
  if (!result.merged) current.error = String(result.message ?? "GitHub rejected the merge").slice(0, 500);
  job.status = result.merged ? "merged" : "merge_failed";
  if (!result.merged) return;

  const target = followUpIssueForPr(repository, approval.pr);
  if (!Number.isInteger(target) || target <= 0) return;
  enqueueOutbox(state, {
    key: `follow-up:${repository}:pr-${approval.pr}:issue-${target}:activate`,
    destination: "github-follow-up-activation",
    payload: { issue: target, pr: Number(approval.pr), repository },
  }, now);
}

export async function activateFollowUp({ github, store, payload, currentRepository, readyLabel }) {
  if (payload.repository !== currentRepository) throw new Error("Follow-up repository does not match current configuration");
  const trustedTarget = followUpIssueForPr(currentRepository, payload.pr);
  if (trustedTarget !== payload.issue) throw new Error("Follow-up target does not match the trusted workflow dependency");
  await github.requireOpenIssue(payload.issue);
  await github.addReadyLabel(payload.issue);
  await store.update((state) => enqueueFollowUpAudit(state, {
    issue: payload.issue,
    pr: payload.pr,
    repository: payload.repository,
    readyLabel,
  }));
}

export function mergeRecoveryDecision({ approval, pull, now = Date.now(), ambiguityMs = 120_000 }) {
  if (pull.merged_at) return "merged";
  const startedAt = Number(approval.processingStartedAt ?? approval.createdAt ?? now);
  return now - startedAt < ambiguityMs ? "wait" : "retry-approval";
}

export function enqueueFollowUpAudit(state, { issue, pr, repository, readyLabel }, now = Date.now()) {
  const prefix = `follow-up:${repository}:pr-${pr}:issue-${issue}`;
  enqueueOutbox(state, {
    key: `${prefix}:audit`,
    destination: "github-follow-up-audit",
    payload: { issue, pr, readyLabel },
  }, now);
  enqueueOutbox(state, {
    key: `${prefix}:discord`,
    destination: "discord-report",
    payload: { content: `▶️ **후속 작업 자동 시작 예약**\nPR #${pr} 병합 완료에 따라 #${issue}을(를) \`${readyLabel}\` 큐에 추가했습니다.` },
  }, now);
}
