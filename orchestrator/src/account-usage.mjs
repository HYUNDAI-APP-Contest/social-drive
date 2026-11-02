import { enqueueOutbox } from "./outbox.mjs";

const MILESTONES = [30, 60, 90];
const SEVEN_DAYS_MINUTES = 7 * 24 * 60;
const finite = (value) => Number.isFinite(Number(value)) ? Number(value) : null;

export function selectSevenDayRateLimit(response) {
  const byId = response?.rateLimitsByLimitId;
  const snapshot = byId?.codex ?? response?.rateLimits ?? response;
  const windows = [snapshot?.primary, snapshot?.secondary].filter(Boolean);
  const window = windows.find((candidate) => finite(candidate?.windowDurationMins) === SEVEN_DAYS_MINUTES);
  return window ? { snapshot, window } : null;
}

export function threadTotalTokens(event) {
  if (event?.event !== "thread/tokenUsage/updated") return null;
  return finite(event?.params?.tokenUsage?.total?.totalTokens);
}

export function recordAccountTokenSnapshot(state, response, { day } = {}) {
  state.accountUsage ??= { milestones: [] };
  const summary = response?.summary ?? {};
  const buckets = Array.isArray(response?.dailyUsageBuckets) ? response.dailyUsageBuckets : [];
  const current = day ? buckets.find((bucket) => bucket.startDate === day) : buckets.at(-1);
  const lifetimeTokens = finite(summary.lifetimeTokens);
  const todayTokens = finite(current?.tokens);
  if (lifetimeTokens !== null) state.accountUsage.lifetimeTokens = lifetimeTokens;
  if (todayTokens !== null) state.accountUsage.todayTokens = todayTokens;
  if (current?.startDate) state.accountUsage.todayDate = current.startDate;
  return state.accountUsage;
}

export function recordAccountRateLimit(state, response, { now = Date.now() } = {}) {
  const selected = selectSevenDayRateLimit(response);
  const usedPercent = finite(selected?.window?.usedPercent);
  if (usedPercent === null) return { claimed: [], current: state.accountUsage ?? null };
  state.accountUsage ??= { milestones: [] };
  const current = state.accountUsage;
  const currentUpdatedAt = Date.parse(current.updatedAt ?? "");
  if (Number.isFinite(currentUpdatedAt) && now < currentUpdatedAt) return { claimed: [], current };
  const resetsAt = finite(selected.window?.resetsAt);
  if (resetsAt !== null && current.resetsAt !== undefined && resetsAt < current.resetsAt) return { claimed: [], current };
  if (resetsAt !== null && current.resetsAt !== resetsAt) current.milestones = [];
  current.usedPercent = Math.max(0, Math.min(100, usedPercent));
  current.remainingPercent = Math.max(0, 100 - current.usedPercent);
  current.windowDurationMins = finite(selected.window?.windowDurationMins);
  if (resetsAt !== null) current.resetsAt = resetsAt;
  if (typeof response?.ordinaryUsageAllowed === "boolean") current.ordinaryUsageAllowed = response.ordinaryUsageAllowed;
  current.updatedAt = new Date(now).toISOString();
  const emitted = new Set(current.milestones ?? []);
  const claimed = MILESTONES.filter((milestone) => current.usedPercent >= milestone && !emitted.has(milestone));
  current.milestones = [...emitted, ...claimed].sort((a, b) => a - b);
  if (claimed.length) {
    const reset = current.resetsAt ? new Date(current.resetsAt * 1000).toISOString() : "확인 불가";
    const tone = claimed.includes(90) ? "⚠️" : claimed.includes(60) ? "🟠" : "🔵";
    const content = `${tone} **Codex 실제 사용 한도 ${claimed.join("% · ")}% 도달**\n7일 한도 사용 ${current.usedPercent}% · 남음 ${current.remainingPercent}%\n초기화: ${reset}\n추가 구매 크레딧 없이 Pro 포함 한도를 기준으로 추적합니다.`;
    enqueueOutbox(state, {
      key: `account-usage:${current.resetsAt ?? "unknown"}:${Math.max(...claimed)}`,
      destination: "discord-report",
      payload: { content },
    }, now);
  }
  return { claimed, current };
}
