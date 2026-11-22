// Reporting interval limits, the same as the platform's ReportingInterval (PATCH /api/v1/admin/sensors/{id}).
export const INTERVAL_MIN_S = 5;
export const INTERVAL_MAX_S = 3600;

/** Why the typed interval can't be saved, or null if it can. */
export function intervalProblem(raw: string): string | null {
  const text = raw.trim();
  if (text === "") return "Enter a number of seconds.";
  if (!/^\d+$/.test(text)) return "Use whole seconds.";
  const n = Number(text);
  if (n < INTERVAL_MIN_S || n > INTERVAL_MAX_S) return `Between ${INTERVAL_MIN_S} and ${INTERVAL_MAX_S} seconds.`;
  return null;
}

/** "15 s", "2 min", "1 h", "1 min 30 s": how often a sensor reports. */
export function intervalLabel(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  if (seconds % 3600 === 0) return `${seconds / 3600} h`;
  const m = Math.floor(seconds / 60), s = seconds % 60;
  return s ? `${m} min ${s} s` : `${m} min`;
}
