import { claimTokenMilestones, milestoneMessage } from "./control-center.mjs";
import { enqueueOutbox } from "./outbox.mjs";

export function recordUsage(state, { day, jobKey, tokens, dailyTokenLimit, estimatedUsdPerMillionTokens, emitMilestones = true, now = Date.now() }) {
  if (!Number.isFinite(tokens) || tokens <= 0) return { claimed: [], current: state.usage?.[day] ?? null };
  state.usage ??= {};
  const current = state.usage[day] ?? { tokens: 0, estimatedUsd: 0, milestones: [] };
  current.tokens += tokens;
  current.estimatedUsd = current.tokens / 1_000_000 * estimatedUsdPerMillionTokens;
  const milestone = emitMilestones
    ? claimTokenMilestones({ tokens: current.tokens, limit: dailyTokenLimit, emitted: current.milestones ?? [] })
    : { claimed: [], emitted: current.milestones ?? [] };
  current.milestones = milestone.emitted;
  state.usage[day] = current;
  if (state.jobs?.[jobKey]) {
    state.jobs[jobKey].tokens = Number(state.jobs[jobKey].tokens ?? 0) + tokens;
    state.jobs[jobKey].updatedAt = new Date(now).toISOString();
  }
  const content = milestoneMessage({ claimed: milestone.claimed, tokens: current.tokens, limit: dailyTokenLimit, estimatedUsd: current.estimatedUsd });
  if (content) enqueueOutbox(state, { key: `token:${day}:${Math.max(...milestone.claimed)}`, destination: "discord-report", payload: { content } }, now);
  return { claimed: milestone.claimed, current };
}
