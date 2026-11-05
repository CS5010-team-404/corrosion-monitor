import { useState } from "react";
import { healthEvents, sensorDetail, sensorsHealth, services as fetchServices } from "../api/endpoints";
import type { HealthStatus } from "../api/types";
import { DataTable, SearchBox } from "../components/DataTable";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Badge, Drawer, Empty, ErrorState, PageHead, PageSkeleton, Skeleton } from "../components/ui";
import { LineChart } from "../components/viz";
import { useAuth } from "../lib/auth";
import { config } from "../lib/config";
import { ago, fmt, label } from "../lib/format";
import { useLive } from "../lib/live";
import { Link, useRouter } from "../lib/router";
import { useAsync } from "../lib/useAsync";

const ORDER: HealthStatus[] = ["OFFLINE", "STUCK", "FAULTY", "DEGRADED", "UNKNOWN", "HEALTHY"];
const ISSUE: Record<string, string> = { OFFLINE: "Offline", STUCK: "Stuck", OUT_OF_RANGE: "Impossible values", ERRATIC: "Erratic", LOSSY: "Losing readings", SIGNATURE_FAILURES: "Bad signatures", CLOCK_SKEW: "Clock off" };
const POLL = config.useMocks ? 3000 : 10000;

export function Health() {
  const { path, navigate } = useRouter();
  const sel = new URLSearchParams(path.split("?")[1] ?? "").get("sensor");
  const s = useAsync(sensorsHealth, [], POLL);
  const ev = useAsync(() => healthEvents(20), [], POLL);
  const sv = useAsync(fetchServices, [], 15000);
  useLive("/topic/health", () => { s.reload(); ev.reload(); });
  const [q, setQ] = useState("");
  const [st, setSt] = useState("all");
  if (s.loading) return <PageSkeleton table />;
  if (s.error) return <ErrorState error={s.error} onRetry={s.reload} />;
  const all = s.data ?? [];
  const rows = all.filter((x) => (st === "all" || x.status === st) && (!q || `${x.deviceId} ${x.equipmentName} ${x.locationName}`.toLowerCase().includes(q.toLowerCase()))).map((x) => ({ ...x, id: x.sensorId }));
  const attention = all.filter((x) => x.status !== "HEALTHY");
  const svc = sv.data ?? [];
  const stat = (k: string, v: React.ReactNode, c: string) => <div><div className="k">{k}</div><div className="v num">{v}</div><div className="c">{c}</div></div>;
  return (
    <>
      <PageHead title="Sensor health" sub="Sensors that go silent, stick, or report impossible values are ignored by analytics until they recover." />
      <div className="card flush" style={{ marginBottom: 24 }}><div className="mini" style={{ gridTemplateColumns: "repeat(4,1fr)" }}>
        {stat("Trusted for analytics", <>{all.filter((x) => x.trusted).length}<small>/ {all.length}</small></>, "readings in use")}
        {stat("Need attention", attention.length, attention.length ? attention.map((x) => x.deviceId).join(", ") : "none")}
        {stat("Open issues", (ev.data ?? []).filter((x) => !x.endedAt).length, "currently active")}
        {stat("Services", <>{svc.filter((x) => x.state === "UP").length}<small>/ {svc.length}</small></>, svc.every((x) => x.state === "UP") ? "all running" : "check below")}
      </div></div>
      <DataTable rows={rows} initialSort={{ key: "status", dir: "asc" }} pageSize={8} onRowClick={(x) => navigate(`/health?sensor=${x.sensorId}`)} isSelected={(x) => String(x.sensorId) === sel}
        tools={<><SearchBox value={q} onChange={setQ} placeholder="Search sensors" /><Dropdown label="Status" value={st} onChange={setSt} options={[{ value: "all", label: "Any status" }, ...ORDER.map((o) => ({ value: o, label: label(o) }))]} /><span className="spacer" /><span className="small muted">Checked every 5 s</span></>}
        columns={[
          { key: "dev", label: "Sensor", sortable: true, sortValue: (x) => x.deviceId, render: (x) => <><div className="cell-main">{x.deviceId}</div><div className="cell-sub">{x.equipmentName}, {x.locationName}</div></> },
          { key: "status", label: "Status", sortable: true, sortValue: (x) => ORDER.indexOf(x.status), render: (x) => <Badge value={x.status} /> },
          { key: "issues", label: "Issues", render: (x) => (x.issues.length ? x.issues.map((i) => ISSUE[i] ?? i).join(", ") : <span className="muted">None</span>) },
          { key: "seen", label: "Last reading", sortable: true, right: true, sortValue: (x) => -(x.lastSeenAt ? Date.parse(x.lastSeenAt) : 0), render: (x) => <span className="num">{ago(x.lastSeenAt)}</span> },
          { key: "missing", label: "Missing", sortable: true, right: true, sortValue: (x) => x.missingPct ?? 0, render: (x) => <span className="num">{fmt(x.missingPct, 0)}%</span> },
          { key: "score", label: "Score", sortable: true, sortValue: (x) => x.score, render: (x) => <div className="score"><div className="bar"><i style={{ width: `${x.score}%`, background: `var(--${x.score > 70 ? "ok" : x.score > 30 ? "warn" : "crit"})` }} /></div><span className="num small">{x.score}</span></div> },
        ]} />
      <div className="section cols">
        <div><div className="sec-head"><h2>Issue history</h2></div><div className="card flush">
          {(ev.data ?? []).length ? (ev.data ?? []).slice(0, 6).map((x, i) => (
            <div key={x.id} className="row between" style={{ padding: "14px 18px", borderTop: i ? "1px solid var(--line)" : undefined }}>
              <div><div className="cell-main">{x.deviceId}: {ISSUE[x.issue] ?? x.issue}</div><div className="cell-sub">Started {ago(x.startedAt)}{x.endedAt ? `, lasted ${Math.max(1, Math.round(x.durationSeconds / 60))} min` : ""}</div></div>
              {x.endedAt ? <Badge value="RESOLVED" text="Closed" /> : <Badge value="OPEN" text="Open" />}
            </div>)) : <Empty title="No issues yet" text="Sensor problems will be listed here." icon="check" />}
        </div></div>
        <div><div className="sec-head"><h2>Background services</h2></div><div className="card flush">
          {svc.map((x, i) => (
            <div key={x.service} className="row between" style={{ padding: "14px 18px", borderTop: i ? "1px solid var(--line)" : undefined }}>
              <div className="row" style={{ gap: 12 }}><span className="tog" style={{ width: 36, height: 36 }}><Icon name="cpu" /></span><div><div className="cell-main">{x.service}</div><div className="cell-sub">Last ran {ago(x.lastRunAt)}{x.lastError ? `, ${x.lastError}` : ", no errors"}</div></div></div>
              <Badge value={x.state} />
            </div>))}
        </div></div>
      </div>
      {sel && <SensorDrawer id={+sel} onClose={() => navigate("/health")} />}
    </>
  );
}

function SensorDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const { can } = useAuth();
  const d = useAsync(() => sensorDetail(id), [id], POLL);
  if (d.loading || !d.data) return <Drawer title="Loading sensor" onClose={onClose}><Skeleton h={20} w="60%" /><Skeleton h={160} /></Drawer>;
  const { sensor: x, readings, events } = d.data;
  return (
    <Drawer title={x.deviceId} onClose={onClose} badges={<Badge value={x.status} />} sub={`${x.model} on ${x.equipmentName}, ${x.locationName}. Reports every ${x.expectedIntervalSeconds} s.`}
      footer={<>{x.equipmentId && <Link className="btn" to={`/equipment/${x.equipmentId}`}>Open {x.equipmentName}</Link>}{can("ADMIN") && <Link className="btn ghost" to="/admin">Simulator</Link>}</>}>
      <dl className="kv"><dt>Health score</dt><dd className="num">{x.score} / 100</dd><dt>Used by analytics</dt><dd>{x.trusted ? "Yes" : "No, until it recovers"}</dd><dt>Last reading</dt><dd className="num">{ago(x.lastSeenAt)}</dd><dt>Missing, 15 min</dt><dd className="num">{fmt(x.missingPct, 0)}%</dd></dl>
      {x.issues.map((i) => <div key={i} className="callout crit"><Icon name="alert" /><div><b>{ISSUE[i] ?? i}.</b> {EXPLAIN[i]}</div></div>)}
      <div><h3 style={{ margin: "6px 0 8px" }}>Humidity, last hour</h3><LineChart width={600} height={230} unit="%" label="Humidity readings, last hour" series={[{ points: readings.map((r) => [Date.parse(r.measuredAt), r.humidityPct]), color: "var(--accent)", area: true }]} /></div>
      {events.length > 0 && <div><h3 style={{ margin: "6px 0 8px" }}>Recent issues</h3>{events.slice(0, 5).map((e) => <div key={e.id} className="row between small" style={{ padding: "8px 0", borderTop: "1px solid var(--line)" }}><span>{ISSUE[e.issue] ?? e.issue}</span><span className="muted">{ago(e.startedAt)}{e.endedAt ? "" : ", open"}</span></div>)}</div>}
    </Drawer>
  );
}

const EXPLAIN: Record<string, string> = {
  OFFLINE: "No readings for more than six reporting intervals. Check power and Wi-Fi.",
  STUCK: "The last 20 readings were identical. The sensor element may have failed.",
  OUT_OF_RANGE: "Values outside what the SHT31 can measure. Check wiring.",
  ERRATIC: "Implausible jumps between readings. Check for loose connections.",
  LOSSY: "More than 25% of readings are missing. Weak network.",
  SIGNATURE_FAILURES: "Readings with an invalid signature were rejected. The device key may be wrong.",
  CLOCK_SKEW: "The device clock is more than 2 minutes off. Enable NTP.",
};
