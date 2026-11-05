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
