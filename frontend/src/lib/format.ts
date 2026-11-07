// Formatting helpers. The clock can be swapped: the mock backend runs faster than real time.
let clock = () => Date.now();
export const setClock = (fn: () => number) => {
  clock = fn;
};
export const now = () => clock();

export const DAY = 86_400_000;
export const fmt = (v: number | null | undefined, d = 1) => (v == null || Number.isNaN(v) ? "–" : v.toFixed(d));
export const label = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ");
const ts = (t: string | number) => (typeof t === "number" ? t : Date.parse(t));
export const time = (t: string | number) => new Date(ts(t)).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
export const dShort = (t: string | number) => new Date(ts(t)).toLocaleDateString([], { month: "short", day: "numeric" });
export const dateTime = (t: string | number) => new Date(ts(t)).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
export function ago(t: string | number | null | undefined) {
  if (t == null) return "never";
  const s = Math.max(0, Math.round((now() - ts(t)) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}
export const startOfDay = (t: number) => {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
};
export const greeting = () => {
  const h = new Date(now()).getHours();
  return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
};
export const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
export type Tone = "ok" | "warn" | "crit" | "muted";
export const marginTone = (m: number, warn: number, crit: number): Tone => (m <= crit ? "crit" : m <= warn ? "warn" : "ok");
