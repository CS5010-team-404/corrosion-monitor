import { clamp, fmt, marginTone, time } from "../lib/format";

/**
 * The signature visual: dew point and surface temperature on one scale. The gap between the markers is the
 * condensation margin; shaded zones show the profile's warning and critical bands.
 */
export function MarginRail({ dew, surface, warn, crit, large, untrusted }: { dew: number | null; surface: number | null; warn: number; crit: number; large?: boolean; untrusted?: boolean }) {
  if (untrusted || dew == null || surface == null) return <span className="small muted">No trusted reading</span>;
  const lo = Math.min(dew, surface) - 4, hi = Math.max(dew + warn * 2.6, surface) + 2;
  const x = (t: number) => clamp(((t - lo) / (hi - lo)) * 100, 0, 100);
  const a = Math.min(dew, surface), b = Math.max(dew, surface), margin = surface - dew;
  return (
    <div className={`rail ${large ? "lg" : ""}`} role="img" aria-label={`Surface ${fmt(surface)} °C, dew point ${fmt(dew)} °C, margin ${fmt(margin)} °C`}>
      <div className="track">
        <div className="band crit" style={{ left: 0, width: `${x(dew + crit)}%` }} />
        <div className="band warn" style={{ left: `${x(dew + crit)}%`, width: `${x(dew + warn) - x(dew + crit)}%` }} />
        <div className={`gap ${marginTone(margin, warn, crit)}`} style={{ left: `${x(a)}%`, width: `${x(b) - x(a)}%` }} />
        <div className="mark dew" style={{ left: `${x(dew)}%` }} /><div className="mark surf" style={{ left: `${x(surface)}%` }} />
        {large && <><div className="lab top num" style={{ left: `${x(dew)}%` }}>Dew point {fmt(dew)} °C</div><div className="lab bot num" style={{ left: `${x(surface)}%` }}>Surface {fmt(surface)} °C</div></>}
      </div>
    </div>
  );
}

export interface Series { points: [number, number][]; color: string; width?: number; dash?: string; area?: boolean }

/** Small SVG line chart with grid, axes, optional area fill and a "now" marker. No chart library needed. */
export function LineChart({ series, unit = "", now, label, width = 900, height = 260 }: { series: Series[]; unit?: string; now?: number; label: string; width?: number; height?: number }) {
  const narrow = typeof window !== "undefined" && window.innerWidth < 700;
  const W = narrow ? 420 : width, H = height, P = { l: 40, r: 8, t: 12, b: 26 };
  const all = series.flatMap((s) => s.points);
  if (!all.length) return <div className="center-load">No data yet</div>;
  const xs = all.map((p) => p[0]), ys = all.map((p) => p[1]);
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.floor(Math.min(...ys) - 1), y1 = Math.ceil(Math.max(...ys) + 1);
  const X = (v: number) => P.l + ((v - x0) / (x1 - x0 || 1)) * (W - P.l - P.r), Y = (v: number) => H - P.b - ((v - y0) / (y1 - y0 || 1)) * (H - P.t - P.b);
  const step = Math.max(1, Math.round((y1 - y0) / 4)), ty: number[] = [];
  for (let v = y0; v <= y1; v += step) ty.push(v);
  const tx = [0, 0.25, 0.5, 0.75, 1].map((f) => x0 + f * (x1 - x0));
  const d = (pts: [number, number][]) => pts.map((p, i) => `${i ? "L" : "M"}${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`).join("");
  return (
    <div className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label}>
        <g className="grid">{ty.map((v) => <line key={v} x1={P.l} x2={W - P.r} y1={Y(v)} y2={Y(v)} />)}</g>
        <g className="axis">
          {ty.map((v) => <text key={v} x={P.l - 10} y={Y(v) + 4} textAnchor="end">{v}{unit}</text>)}
          {tx.map((v, i) => <text key={i} x={X(v)} y={H - 6} textAnchor={i === 0 ? "start" : i === tx.length - 1 ? "end" : "middle"}>{time(v)}</text>)}
        </g>
        {series.filter((s) => s.area && s.points.length).map((s, i) => (
          <path key={`a${i}`} d={`M${X(s.points[0][0])},${H - P.b} ${s.points.map((p) => `L${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`).join(" ")} L${X(s.points[s.points.length - 1][0])},${H - P.b}Z`} fill={s.color} opacity={0.07} />
        ))}
        {series.map((s, i) => <path key={i} d={d(s.points)} fill="none" stroke={s.color} strokeWidth={s.width ?? 2} strokeDasharray={s.dash} strokeLinejoin="round" strokeLinecap="round" />)}
        {now && <><line x1={X(now)} x2={X(now)} y1={P.t} y2={H - P.b} stroke="var(--line-2)" strokeDasharray="3 4" /><text x={X(now) + 6} y={P.t + 10} fill="var(--text-3)" fontSize={11}>Now</text></>}
      </svg>
    </div>
  );
}
