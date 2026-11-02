export const PROGRESS_MARKER = "social-drive-control-center";
export const TOKEN_MILESTONES = [30, 60, 90];
// Routine transitions stay on GitHub/Project/dashboard. Discord receives the smaller
// visual outcome stream plus separate approval cards, avoiding duplicate channel spam.
export const DISCORD_STAGE_STATUSES = new Set();

export function discordStageEventKey(issueKey, job) {
  const status = safeLine(job?.status || "unknown", 48);
  const attempt = Math.max(0, Number(job?.attempts ?? 0));
  const reviewRound = Math.max(0, Number(job?.reviewRound ?? 0));
  return `stage:${safeLine(issueKey, 64)}:${status}:${attempt}:${reviewRound}`;
}

const contract = (status, phase, health, persona, progress, stage, nextAction) => ({
  status,
  phase,
  health,
  persona,
  progress,
  stage,
  nextAction,
});

export const STATUS_CONTRACT = Object.freeze({
  ready: contract("Ready", "Queue", "On track", "Orchestrator", 0, "ready", "빈 슬롯이 생기면 자동 시작합니다."),
  removed_from_queue: contract("Cancelled", "Queue", "On track", "Orchestrator", 0, "cancelled", "agent:ready 라벨을 다시 붙이면 대기열에 재등록됩니다."),
  preparing: contract("In progress", "Queue", "On track", "Orchestrator", 5, "build", "격리 작업공간을 준비합니다."),
  running: contract("In progress", "Queue", "On track", "Orchestrator", 10, "build", "3인 개발 회의를 시작합니다."),
  planning: contract("In progress", "Planning", "On track", "아키", 15, "planning", "범위와 완료 기준을 확정합니다."),
  implementing: contract("In progress", "Build", "On track", "픽시", 40, "build", "구현과 검사를 완료한 뒤 독립 검토로 넘깁니다."),
  reviewing: contract("Review", "Review", "On track", "센티넬", 70, "review", "변경과 테스트 증거를 독립 검토합니다."),
  reworking: contract("Review", "Build", "At risk", "픽시", 65, "review", "검토 의견을 반영한 뒤 다시 검토합니다."),
  publishing: contract("Review", "PR", "On track", "Orchestrator", 85, "review", "승인 가능한 PR을 준비합니다."),
  awaiting_merge_approval: contract("Approval needed", "Merge", "At risk", "Human", 90, "approval", "Discord에서 PR 병합 여부를 결정해 주세요."),
  awaiting_deploy_approval: contract("Approval needed", "Deploy", "At risk", "Human", 95, "approval", "Discord에서 dev 배포 여부를 결정해 주세요."),
  merge_approval_expired: contract("Blocked", "Merge", "Blocked", "Human", 90, "blocked", "병합 승인이 만료되었습니다. 새 승인 요청이 필요합니다."),
  deploy_approval_expired: contract("Blocked", "Deploy", "Blocked", "Human", 95, "blocked", "배포 승인이 만료되어 변경 세트를 실행하지 않았습니다."),
  retry: contract("Blocked", "Queue", "Recovering", "Orchestrator", 10, "blocked", "보존된 상태에서 자동 재시도합니다."),
  review_retry: contract("Review", "Review", "At risk", "픽시", 65, "review", "센티넬 수정 요청을 반영하는 다음 개발 주기를 시작합니다."),
  paused_usage_limit: contract("Blocked", "Queue", "Blocked", "Orchestrator", 10, "blocked", "사용량 한도가 풀리면 자동 재개합니다."),
  merge_failed: contract("Blocked", "Merge", "Blocked", "Orchestrator", 90, "blocked", "병합 실패 원인을 확인한 뒤 다시 결정합니다."),
  deploy_failed: contract("Blocked", "Deploy", "Blocked", "Orchestrator", 95, "blocked", "배포 실패 원인을 확인한 뒤 다시 결정합니다."),
  failed: contract("Blocked", "Queue", "Blocked", "Orchestrator", 0, "blocked", "자동 재시도 한도를 소진했습니다. 작업 범위를 확인해 주세요."),
  review_blocked: contract("Blocked", "Review", "At risk", "센티넬", 70, "blocked", "코드와 검토 기록은 보존됐습니다. 반복된 품질 수정 요청을 확인해 주세요."),
  rejected: contract("Blocked", "Merge", "Blocked", "Human", 90, "blocked", "병합이 보류되었습니다. 후속 방향을 결정해 주세요."),
  deploy_rejected: contract("Blocked", "Deploy", "Blocked", "Human", 95, "blocked", "dev 배포가 취소되었습니다. 코드 병합 상태는 유지됩니다."),
  merged: contract("Done", "Merge", "On track", "Orchestrator", 100, "done", "병합이 완료되었습니다."),
  deployed: contract("Done", "Deploy", "On track", "Orchestrator", 100, "done", "dev 배포와 확인이 완료되었습니다."),
});

const fallback = contract("Blocked", "Queue", "At risk", "Orchestrator", 0, "blocked", "알 수 없는 상태입니다. 운영 로그를 확인해 주세요.");
const managedLabels = ["ready", "planning", "build", "review", "approval", "blocked", "cancelled", "done"];
const personaLabels = { Orchestrator: "orchestrator", "아키": "aki", "픽시": "pixie", "센티넬": "sentinel", Human: "human" };

export const mappingFor = (status) => STATUS_CONTRACT[status] ?? fallback;
export const controlLabelsFor = (status) => {
  const mapped = mappingFor(status);
  return [`agent:stage:${mapped.stage}`, `agent:persona:${personaLabels[mapped.persona] ?? "orchestrator"}`];
};
export const managedControlLabels = () => [
  ...managedLabels.map((name) => `agent:stage:${name}`),
  ...Object.values(personaLabels).map((name) => `agent:persona:${name}`),
];

const safeLine = (value, max = 160) => String(value ?? "")
  .replace(/[\u0000-\u001f\u007f]/g, " ")
  .replace(/\s+/g, " ")
  .trim()
  .slice(0, max);

const tokenPercent = (tokens, limit) => limit > 0 ? Math.min(100, Math.floor((tokens / limit) * 100)) : null;

export function claimTokenMilestones({ tokens, limit, emitted = [] }) {
  const percent = tokenPercent(tokens, limit) ?? 0;
  const already = new Set(emitted);
  const claimed = TOKEN_MILESTONES.filter((milestone) => percent >= milestone && !already.has(milestone));
  return { percent, claimed, emitted: [...new Set([...emitted, ...claimed])].sort((a, b) => a - b) };
}

export function milestoneMessage({ claimed, tokens, limit, estimatedUsd = 0 }) {
  if (!claimed.length) return null;
  const joined = claimed.map((value) => `${value}%`).join(" · ");
  const percent = tokenPercent(tokens, limit);
  const tone = claimed.includes(90) ? "⚠️" : claimed.includes(60) ? "🟠" : "🔵";
  const guidance = claimed.includes(90)
    ? "새 작업은 보류하고 진행 중인 작업만 안전하게 마무리합니다."
    : claimed.includes(60)
      ? "남은 예산을 확인하며 진행합니다."
      : "작업은 정상 진행 중입니다.";
  return `${tone} **오늘 토큰 예산 ${joined} 도달**\n사용: ${Number(tokens).toLocaleString("en-US")} / ${Number(limit).toLocaleString("en-US")} (${percent}%)\n예상 비용: $${Number(estimatedUsd).toFixed(2)}\n${guidance}`;
}

export function buildProgressSnapshot(job, { dailyUsage = {}, dailyTokenLimit, approval = null, now = new Date() } = {}) {
  const mapped = mappingFor(job?.status);
  const updatedAt = job?.updatedAt && !Number.isNaN(Date.parse(job.updatedAt)) ? job.updatedAt : now.toISOString();
  const approvalType = approval?.type === "aws-deploy" ? "Deploy" : approval?.type === "merge" ? "Merge" : "None";
  return Object.freeze({
    issue: Number(job?.issue ?? 0),
    issueNodeId: safeLine(job?.issueNodeId, 128),
    title: safeLine(job?.title || "제목 없음", 120),
    internalStatus: safeLine(job?.status || "unknown", 48),
    ...mapped,
    labels: controlLabelsFor(job?.status),
    attempts: Math.max(0, Number(job?.attempts ?? 0)),
    reviewRound: Math.max(0, Number(job?.reviewRound ?? 0)),
    tokens: Math.max(0, Number(job?.tokens ?? 0)),
    dailyTokens: Math.max(0, Number(dailyUsage.tokens ?? 0)),
    dailyTokenPercent: tokenPercent(Number(dailyUsage.tokens ?? 0), dailyTokenLimit),
    branch: safeLine(job?.branch, 120),
    pr: Number(job?.pr ?? 0) || null,
    prUrl: /^https:\/\/github\.com\//.test(job?.prUrl ?? "") ? job.prUrl : null,
    approvalType,
    approvalExpiresAt: approval?.expiresAt ? new Date(approval.expiresAt).toISOString() : null,
    updatedAt,
    nextRetryAt: job?.nextRetryAt ? new Date(job.nextRetryAt).toISOString() : null,
    nextAction: mapped.nextAction,
  });
}

const statusHeadline = (snapshot) => {
  if (snapshot.internalStatus === "reviewing") return `센티넬 독립 검토 중${snapshot.reviewRound ? ` · ${snapshot.reviewRound}차` : ""}`;
  if (snapshot.internalStatus === "reworking") return `픽시 검토 의견 반영 중${snapshot.reviewRound ? ` · ${snapshot.reviewRound}차` : ""}`;
  if (snapshot.internalStatus === "paused_usage_limit") return "사용량 한도로 일시 정지";
  if (snapshot.internalStatus === "retry") return "자동 복구 대기";
  if (snapshot.internalStatus === "review_retry") return "품질 수정 주기 대기";
  return `${snapshot.phase} · ${snapshot.persona}`;
};

export function renderProgressComment(snapshot) {
  const pr = snapshot.prUrl ? `[#${snapshot.pr}](${snapshot.prUrl})` : snapshot.pr ? `#${snapshot.pr}` : "아직 없음";
  const branch = snapshot.branch ? `\`${snapshot.branch}\`` : "준비 전";
  const token = `${snapshot.tokens.toLocaleString("en-US")} tokens`;
  const daily = snapshot.dailyTokenPercent === null ? "확인 불가" : `${snapshot.dailyTokenPercent}%`;
  const retry = snapshot.nextRetryAt ? `\n- 자동 재시도: ${snapshot.nextRetryAt}` : "";
  return `<!-- ${PROGRESS_MARKER}:issue-${snapshot.issue} -->\n## 🤖 자동 개발 현황\n**${snapshot.status} · ${statusHeadline(snapshot)}**  \`${snapshot.progress}%\`\n\n| 현재 담당 | Health | 시도 / 검토 | 작업 토큰 |\n|---|---|---:|---:|\n| ${snapshot.persona} | ${snapshot.health} | ${snapshot.attempts} / ${snapshot.reviewRound || "-"} | ${token} |\n\n- 마지막 확인: ${snapshot.updatedAt}\n- 다음 단계: ${snapshot.nextAction}\n- 브랜치: ${branch}\n- PR: ${pr}\n- 오늘 토큰 예산: ${daily}${retry}\n\n> 병합과 배포는 Discord 승인 전까지 실행되지 않습니다. 세부 오류와 비밀정보는 이 코멘트에 기록하지 않습니다.\n`;
}

export function renderDiscordStageReport(snapshot) {
  const emoji = snapshot.status === "Approval needed"
    ? "🟠"
    : snapshot.status === "Blocked"
      ? "🔴"
      : snapshot.phase === "Review"
        ? "🛡️"
        : snapshot.phase === "Build"
          ? "🎨"
          : snapshot.phase === "Planning"
            ? "🧭"
            : "📦";
  const review = snapshot.reviewRound ? ` · 검토 ${snapshot.reviewRound}차` : "";
  const pr = snapshot.prUrl ? `\nPR: ${snapshot.prUrl}` : "";
  return `${emoji} **#${snapshot.issue} ${snapshot.phase} · ${snapshot.persona}**${review}\n${snapshot.title}\n진행 ${snapshot.progress}% · ${snapshot.health}\n다음: ${snapshot.nextAction}${pr}`.slice(0, 1800);
}

const attentionRank = (snapshot) => {
  if (snapshot.status === "Approval needed") return snapshot.approvalExpiresAt ? 0 : 1;
  if (snapshot.health === "Blocked") return 2;
  if (snapshot.health === "At risk") return 3;
  if (snapshot.health === "Recovering") return 4;
  if (snapshot.status === "In progress" || snapshot.status === "Review") return 5;
  if (snapshot.status === "Ready") return 6;
  return 7;
};

const relativeExpiry = (expiresAt, now) => {
  if (!expiresAt) return "만료 시각 확인 불가";
  const remaining = Date.parse(expiresAt) - now.getTime();
  if (remaining <= 0) return "만료됨";
  const minutes = Math.ceil(remaining / 60_000);
  return minutes >= 60 ? `${Math.floor(minutes / 60)}시간 ${minutes % 60}분 후 만료` : `${minutes}분 후 만료`;
};

export function renderAgentStatus({ jobs, approvals = [], usage = {}, accountUsage = null, config, activeCount = 0, deploymentActive = false, now = new Date() }) {
  const pending = approvals.filter((item) => item.status === "pending");
  const snapshots = jobs.map((job) => {
    const approval = pending.find((item) => Number(item.issue) === Number(job.issue)) ?? null;
    return buildProgressSnapshot(job, { dailyUsage: usage, dailyTokenLimit: config.dailyTokenLimit, approval, now });
  }).filter((item) => item.status !== "Done").sort((a, b) => attentionRank(a) - attentionRank(b) || Date.parse(a.updatedAt) - Date.parse(b.updatedAt));
  const blocked = snapshots.filter((item) => item.status === "Blocked").length;
  const decision = snapshots.filter((item) => item.status === "Approval needed").length;
  const health = blocked ? "🔴 확인 필요" : decision ? "🟠 결정 대기" : "🟢 정상";
  const lines = snapshots.slice(0, 5).map((item) => {
    const detail = item.status === "Approval needed" ? `${item.approvalType} · ${relativeExpiry(item.approvalExpiresAt, now)}` : `${item.persona} · ${item.progress}% · ${item.health}`;
    return `[${item.status}] #${item.issue} ${safeLine(item.title, 42)} · ${detail}`;
  });
  if (!lines.length) lines.push("대기 중 · `agent:ready` Issue가 없습니다.");
  if (snapshots.length > 5) lines.push(`외 ${snapshots.length - 5}개 · 전체 보기`);
  const tokens = Math.max(0, Number(usage.tokens ?? 0));
  const project = config.githubProjectUrl ? `\n전체 보기: ${config.githubProjectUrl}` : "";
  const overflow = snapshots.length > 5 && !config.githubProjectUrl ? " (GitHub Project URL 미설정)" : "";
  if (accountUsage && Number.isFinite(Number(accountUsage.usedPercent))) {
    const reset = accountUsage.resetsAt ? new Date(Number(accountUsage.resetsAt) * 1000).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" }) : "확인 불가";
    return `${health} · Agent ${activeCount}/${config.maxParallel} 실행 중\n결정 필요 ${decision} · 차단 ${blocked} · 대기 ${snapshots.filter((item) => item.status === "Ready").length}\n\n${lines.join("\n")}${overflow}\n\nCodex 실제 7일 한도 ${Number(accountUsage.usedPercent)}% 사용 · ${Number(accountUsage.remainingPercent)}% 남음\n오늘 ${Number(accountUsage.todayTokens ?? 0).toLocaleString("en-US")} tokens · 누적 ${Number(accountUsage.lifetimeTokens ?? 0).toLocaleString("en-US")} tokens\n초기화 ${reset}\nAWS: ${deploymentActive ? "작업 중" : "대기"} · 배포 승인 ${pending.filter((item) => item.type === "aws-deploy").length}${project}`.slice(0, 1900);
  }
  const percent = tokenPercent(tokens, config.dailyTokenLimit);
  const next = TOKEN_MILESTONES.find((value) => value > (percent ?? 0));
  const nextTokens = next ? Math.max(0, Math.ceil((config.dailyTokenLimit * next) / 100 - tokens)) : Math.max(0, config.dailyTokenLimit - tokens);
  return `${health} · Agent ${activeCount}/${config.maxParallel} 실행 중\n결정 필요 ${decision} · 차단 ${blocked} · 대기 ${snapshots.filter((item) => item.status === "Ready").length}\n\n${lines.join("\n")}${overflow}\n\n오늘 토큰 ${tokens.toLocaleString("en-US")} / ${config.dailyTokenLimit.toLocaleString("en-US")} (${percent ?? "확인 불가"}%)\n다음 한도: ${next ? `${next}%까지 ` : "일일 한도까지 "}${nextTokens.toLocaleString("en-US")} · 예상 비용 $${Number(usage.estimatedUsd ?? 0).toFixed(2)} / $${Number(config.dailyCostLimitUsd).toFixed(2)}\nAWS: ${deploymentActive ? "작업 중" : "대기"} · 배포 승인 ${pending.filter((item) => item.type === "aws-deploy").length}${project}`.slice(0, 1900);
}

export const CONTROL_CENTER_SCHEMA = Object.freeze({
  title: "Social Drive · Development Control Center",
  views: [
    { name: "Command Center", layout: "BOARD_LAYOUT", filter: "-status:Done,Cancelled", fields: ["Title", "Status", "Phase", "Health", "Current owner", "Progress"] },
    { name: "Now", layout: "TABLE_LAYOUT", filter: "status:Ready,\"In progress\",Review", fields: ["Title", "Status", "Phase", "Health", "Current owner", "Progress", "Tokens", "Next action"] },
    { name: "Needs attention", layout: "TABLE_LAYOUT", filter: "status:\"Approval needed\",Blocked", fields: ["Title", "Status", "Health", "Approval type", "Approval expires", "Next action"] },
    { name: "PR & Release", layout: "TABLE_LAYOUT", filter: "phase:PR,Merge,Deploy", fields: ["Title", "Status", "Phase", "Approval type", "Approval expires", "Last activity"] },
    { name: "History", layout: "TABLE_LAYOUT", filter: "status:Done,Cancelled", fields: ["Title", "Status", "Phase", "Last activity", "Tokens"] },
  ],
  fields: [
    { name: "Status", dataType: "SINGLE_SELECT", options: ["Backlog", "Ready", "In progress", "Review", "Approval needed", "Blocked", "Cancelled", "Done"] },
    { name: "Phase", dataType: "SINGLE_SELECT", options: ["Queue", "Planning", "Build", "Review", "PR", "Merge", "Deploy"] },
    { name: "Health", dataType: "SINGLE_SELECT", options: ["On track", "At risk", "Blocked", "Recovering"] },
    { name: "Current owner", dataType: "SINGLE_SELECT", options: ["Orchestrator", "아키", "픽시", "센티넬", "Human"] },
    { name: "Progress", dataType: "NUMBER" },
    { name: "Tokens", dataType: "NUMBER" },
    { name: "Attempt", dataType: "NUMBER" },
    { name: "Review round", dataType: "NUMBER" },
    { name: "Approval type", dataType: "SINGLE_SELECT", options: ["None", "Merge", "Deploy"] },
    { name: "Approval expires", dataType: "DATE" },
    { name: "Last activity", dataType: "DATE" },
    { name: "Next action", dataType: "TEXT" },
  ],
});
