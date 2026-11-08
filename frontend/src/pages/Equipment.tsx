import { useState } from "react";
import { alerts as fetchAlerts, assessments, equipmentStatus, forecast as fetchForecast } from "../api/endpoints";
import { Art } from "../components/Art";
import { Menu } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Badge, ErrorState, PageSkeleton, Segmented, Skeleton } from "../components/ui";
import { LineChart, MarginRail } from "../components/viz";
import { isNotBuilt, saveBlob } from "../lib/api";
import { config } from "../lib/config";
import { ago, clamp, dShort, fmt, label, marginTone } from "../lib/format";
import { useLive } from "../lib/live";
import { Link, useRouter } from "../lib/router";
import { useToast } from "../lib/toast";
import { useAsync } from "../lib/useAsync";

const POLL = config.useMocks ? 3000 : 15000;

export function Equipment({ id }: { id: number }) {
  const { navigate } = useRouter();
  const toast = useToast();
  const [range, setRange] = useState(120);
  const st = useAsync(equipmentStatus, [], POLL);
  const pts = useAsync(() => assessments(id, range), [id, range], POLL);
  const e = st.data?.find((x) => x.equipmentId === id);
  const fc = useAsync(() => (e?.sensorId ? fetchForecast(e.sensorId) : Promise.resolve(null)), [e?.sensorId], POLL);
  const al = useAsync(() => fetchAlerts("ALL", 200), [], 30000);
  useLive("/topic/risk", () => { st.reload(); pts.reload(); });

  if (st.loading) return <PageSkeleton />;
  if (st.error) return <ErrorState error={st.error} onRetry={st.reload} />;
  if (!e) return <ErrorState error={new Error(`Equipment ${id} was not found.`)} onRetry={() => navigate("/")} />;

  // A forecast from an untrusted sensor is not shown, like its current figures.
  const f = e.trusted ? fc.data : null;
  const m60 = e.trusted ? (f?.margin60C ?? e.forecastMargin60C) : null;
  const series = (pts.data ?? []).map((p) => ({ t: Date.parse(p.measuredAt), ...p }));
  const last = series[series.length - 1];
  const chart = series.length ? [
    { points: series.map((p) => [p.t, p.temperatureC] as [number, number]), color: "var(--line-2)", width: 1.5 },
    { points: series.map((p) => [p.t, p.surfaceTempC] as [number, number]), color: "var(--surf)", area: true },
    { points: series.map((p) => [p.t, p.dewPointC] as [number, number]), color: "var(--dew)" },
    ...(f && last ? [
      { points: [[last.t, last.surfaceTempC], [last.t + 15 * 60000, last.dewPointC + f.margin15C], [last.t + 30 * 60000, last.dewPointC + f.margin30C], [last.t + 60 * 60000, last.dewPointC + f.margin60C]] as [number, number][], color: "var(--surf)", dash: "5 5" },
      { points: [[last.t, last.dewPointC], [last.t + 60 * 60000, last.dewPointC]] as [number, number][], color: "var(--dew)", dash: "5 5" },
    ] : []),
  ] : [];
  // GET /api/v1/equipment/{id}/assessments isn't built yet: the chart says so, the rest of the page uses real data.
  const historyMissing = isNotBuilt(pts.error);
  const myAlerts = (al.data ?? []).filter((a) => a.equipmentId === id).slice(0, 4);
  const exportCsv = () => {
    const rows = ["measured_at,temperature_c,humidity_pct,surface_temp_c,dew_point_c,margin_c", ...(pts.data ?? []).map((p) => [p.measuredAt, p.temperatureC, p.humidityPct, p.surfaceTempC, p.dewPointC, p.marginC].map((v) => (typeof v === "number" ? v.toFixed(2) : v)).join(","))];
    saveBlob(new Blob([rows.join("\n")], { type: "text/csv" }), `${e.equipmentName.replace(/\W+/g, "-").toLowerCase()}-readings.csv`);
    toast("Readings downloaded");
  };
  // "–" on its own when a value is missing, never "–°C".
  const deg = (v: number | null) => (v == null ? "–" : <>{fmt(v)}<small>°C</small></>);
  const withUnit = (v: number | null, unit: string) => (v == null ? "–" : `${fmt(v)}${unit}`);
  const tile = (icon: string, val: React.ReactNode, name: string, sub: string, on?: boolean) => (
    <div className="tile" style={{ cursor: "default" }}><div className="top"><div className="val num">{val}</div><span className={`tog ${on ? "on" : ""}`}><Icon name={icon} /></span></div><div><div className="name">{name}</div><div className="sub">{sub}</div></div></div>
  );

  return (
    <>
      <nav className="crumbs" aria-label="Breadcrumb"><Link to="/">Overview</Link><Icon name="chevR" /><span>{e.locationName}</span><Icon name="chevR" /><span style={{ color: "var(--text)" }}>{e.equipmentName}</span></nav>
      <section className="scene" aria-label={e.equipmentName}>
        <div className="bar">
          <Link className="circle-btn" to="/" aria-label="Back"><Icon name="chevL" /></Link>
          <span className="ttl">{e.equipmentName}</span>
          <Menu width={230} trigger={(toggle) => <button className="circle-btn" onClick={toggle} aria-label="More actions"><Icon name="more" /></button>}>
            {(close) => <>
              {!historyMissing && <button className="opt" onClick={() => { close(); exportCsv(); }}><span className="ck"><Icon name="download" /></span>Export readings (CSV)</button>}
              {e.sensorId && <button className="opt" onClick={() => { close(); navigate(`/health?sensor=${e.sensorId}`); }}><span className="ck"><Icon name="wifi" /></span>Open sensor {e.deviceId}</button>}
              <button className="opt" onClick={() => { close(); navigate(`/alerts?q=${encodeURIComponent(e.equipmentName)}`); }}><span className="ck"><Icon name="bell" /></span>View alerts</button>
            </>}
          </Menu>
        </div>
        <div className="art"><Art kind={e.profileKind} /></div>
        <div className="gauge" aria-label={`Humidity ${fmt(e.humidityPct, 0)} percent`}><div className="fill" style={{ height: `${clamp(e.humidityPct ?? 0, 0, 100)}%` }} /><span className="knob"><Icon name="drop" /></span><div className="pct num">{fmt(e.humidityPct, 0)}%<small>humidity</small></div></div>
        <div className="status"><div className="small muted">{e.profileName} profile</div>
          <div className="row" style={{ gap: 10, marginTop: 4 }}><b className="num" style={{ fontSize: 24, letterSpacing: "-.02em" }}>{e.trusted ? `${fmt(e.marginC)} °C` : "–"}</b><span className="small muted">margin</span>{e.trusted ? <Badge value={e.riskLevel} /> : <Badge text="No trusted data" />}</div></div>
      </section>

      <div className="section" style={{ marginTop: 28 }}>
        <div className="sec-head"><h2>Readings</h2><span className="small muted">Updated {ago(e.updatedAt)}</span></div>
        <div className="tiles">
          {tile("thermo", deg(e.temperatureC), "Air temperature", `Allowed ${e.tempMinC} to ${e.tempMaxC} °C`)}
          {tile("wind", deg(e.dewPointC), "Dew point", "Condensation forms below this")}
          {tile("layers", deg(e.surfaceTempC), "Surface", `Air minus ${e.surfaceOffsetC} °C cold-spot offset`)}
          {tile("clock", deg(m60), "Margin in 60 min", f ? `${f.method === "model" ? "ML forecast" : "Trend"}${f.warn60 ? ", early warning on" : ""}` : "Forecast pending", m60 != null && marginTone(m60, e.marginWarningC, e.marginCriticalC) !== "ok")}
        </div>
      </div>

      <div className="section"><div className="card">
        <div className="card-head"><div><h2>Temperature and forecast</h2><p className="small muted" style={{ margin: "4px 0 0" }}>Dashed lines after now are the 60-minute forecast{f ? ` (${f.modelVersion})` : ""}</p></div>
          <Segmented label="Range" value={range} onChange={setRange} items={[{ key: 60, label: "1 h" }, { key: 120, label: "2 h" }, { key: 240, label: "4 h" }]} /></div>
        <MarginRail large dew={e.dewPointC} surface={e.surfaceTempC} warn={e.marginWarningC} crit={e.marginCriticalC} untrusted={!e.trusted} />
        {historyMissing ? <div className="empty" role="status"><div className="circ"><Icon name="clock" /></div><b>Not built yet</b>{(pts.error as Error).message}</div>
          : pts.loading ? <Skeleton h={250} /> : <LineChart series={chart} unit="°" now={last?.t} label="Air, surface and dew point temperature with forecast" />}
        <div className="legend"><span><i style={{ background: "var(--surf)" }} />Surface</span><span><i style={{ background: "var(--dew)" }} />Dew point</span><span><i style={{ background: "var(--line-2)" }} />Air</span></div>
      </div></div>

      <div className="section cols">
        <div className="card"><div className="card-head"><h2>Risk breakdown</h2><span className="badge muted num">Index {e.riskIndex ?? "–"}</span></div>
          <dl className="kv">
            <dt>Margin</dt><dd className="num">{withUnit(e.marginC, " °C")} (warning {e.marginWarningC} °C)</dd>
            <dt>Humidity</dt><dd className="num">{withUnit(e.humidityPct, "%")} (critical {e.rhCriticalPct}%)</dd>
            <dt>Time of wetness</dt><dd className="num">{e.timeOfWetnessMin24h == null ? "–" : `${e.timeOfWetnessMin24h} min above 80% RH`}</dd>
            <dt>Early warning</dt><dd className="num">{f ? `${Math.round(f.risk30 * 100)}% within 30 min, ${Math.round(f.risk60 * 100)}% within 60` : "–"}</dd>
          </dl></div>
        <div className="card"><div className="card-head"><h2>Sensor and alerts</h2>{e.trusted ? <Badge value="HEALTHY" text="Trusted" /> : <Badge value="FAULTY" text="Not trusted" />}</div>
          <dl className="kv"><dt>Sensor</dt><dd>{e.sensorId ? <Link to={`/health?sensor=${e.sensorId}`}>{e.deviceId}</Link> : "None"}</dd><dt>Last reading</dt><dd className="num">{ago(e.updatedAt)}</dd></dl>
          <div className="small muted" style={{ margin: "18px 0 6px" }}>Recent alerts</div>
          {myAlerts.length ? myAlerts.map((a) => (
            <div key={a.id} className="row between" style={{ padding: "9px 0", borderTop: "1px solid var(--line)" }}><Link to={`/alerts?alert=${a.id}`}>{a.title.replace(`: ${e.equipmentName}`, "")}</Link><span className="row small muted">{dShort(a.openedAt)} <Badge value={a.status} text={label(a.status)} /></span></div>
          )) : <p className="small muted" style={{ margin: 0 }}>No alerts for this equipment.</p>}
        </div>
      </div>
    </>
  );
}
