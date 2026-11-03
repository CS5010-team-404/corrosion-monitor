import { useMemo, useState } from "react";
import { alerts as fetchAlerts, equipmentStatus } from "../api/endpoints";
import type { EquipmentStatus, RiskLevel } from "../api/types";
import { Art } from "../components/Art";
import { DataTable, SearchBox } from "../components/DataTable";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Badge, Empty, ErrorState, PageHead, PageSkeleton, Segmented } from "../components/ui";
import { MarginRail } from "../components/viz";
import { useAuth } from "../lib/auth";
import { config } from "../lib/config";
import { fmt, greeting, label, marginTone, now } from "../lib/format";
import { useLive } from "../lib/live";
import { Link, useRouter } from "../lib/router";
import { useAsync } from "../lib/useAsync";

const POLL = config.useMocks ? 3000 : 15000;
const levelOrder: RiskLevel[] = ["CRITICAL", "WARNING", "UNKNOWN", "NORMAL"];
const levelOf = (e: EquipmentStatus): RiskLevel => (e.trusted ? e.riskLevel : "UNKNOWN");

export function Overview() {
  const { session, can } = useAuth();
  const { navigate } = useRouter();
  const eq = useAsync(equipmentStatus, [], POLL);
  const al = useAsync(() => fetchAlerts("ACTIVE", 100), [], POLL);
  useLive("/topic/risk", () => eq.reload());
  const [view, setView] = useState<"tiles" | "table">("tiles");
  const [q, setQ] = useState("");
  const [loc, setLoc] = useState("all");
  const [st, setSt] = useState("all");

  const rows = eq.data ?? [];
  const locations = useMemo(() => [...new Set(rows.map((e) => e.locationName))], [rows]);
  const list = rows.filter((e) => (loc === "all" || e.locationName === loc) && (st === "all" || levelOf(e) === st) && (!q || (e.equipmentName + e.locationName + (e.deviceId ?? "")).toLowerCase().includes(q.toLowerCase())));

  if (eq.loading) return <PageSkeleton />;
  if (eq.error) return <ErrorState error={eq.error} onRetry={eq.reload} />;

  const trusted = rows.filter((e) => e.trusted && e.marginC != null).sort((a, b) => a.marginC! - b.marginC!);
  const top = trusted[0];
  const atRisk = trusted.filter((e) => e.riskLevel !== "NORMAL").length;
  const active = (al.data ?? []).filter((a) => a.status !== "RESOLVED");
  const avgRh = trusted.reduce((s, e) => s + (e.humidityPct ?? 0), 0) / (trusted.length || 1);
  const minutesTo = top?.predictedCondensationAt ? Math.max(1, Math.round((Date.parse(top.predictedCondensationAt) - now()) / 60000)) : null;
  const filters = (
    <>
      <SearchBox value={q} onChange={setQ} placeholder="Search equipment" />
      <Dropdown label="Location" value={loc} onChange={setLoc} options={[{ value: "all", label: "All locations" }, ...locations.map((l) => ({ value: l, label: l }))]} />
      <Dropdown label="Status" value={st} onChange={setSt} options={[{ value: "all", label: "Any status" }, { value: "NORMAL", label: "Normal" }, { value: "WARNING", label: "Warning" }, { value: "CRITICAL", label: "Critical" }, { value: "UNKNOWN", label: "No trusted data" }]} />
    </>
  );

  return (
    <>
      <PageHead title={`${greeting()}, ${session!.fullName.split(" ")[0]}`} sub={`Here is how close each piece of equipment is to condensation across ${locations.length} locations.`} />
      <div className="grid-hero">
        {top && (
          <section className="night" aria-label="Closest to condensation">
            <div className="row between" style={{ alignItems: "flex-start" }}>
              <div>
                <div className="lbl">{new Date(now()).toLocaleDateString([], { weekday: "short", day: "numeric", month: "long" })}, closest to condensation</div>
                <div className="title">{top.equipmentName}</div>
                <div className="sub">{top.locationName}. {minutesTo ? `Forecast reaches condensation in about ${minutesTo} min.` : "The margin is steady or widening."}</div>
              </div>
              <div className={`big num t-${marginTone(top.marginC!, top.marginWarningC, top.marginCriticalC)}`}>{fmt(top.marginC)}°<small>margin to condensation</small></div>
            </div>
            <MarginRail large dew={top.dewPointC} surface={top.surfaceTempC} warn={top.marginWarningC} crit={top.marginCriticalC} />
            <div className="stats">
              <div className="stat"><span className="circ"><Icon name="drop" /></span><div><div className="k">Humidity</div><div className="v num">{fmt(top.humidityPct, 0)}%</div></div></div>
              <div className="stat"><span className="circ"><Icon name="wind" /></span><div><div className="k">Dew point</div><div className="v num">{fmt(top.dewPointC)} °C</div></div></div>
              <div className="stat"><span className="circ"><Icon name="clock" /></span><div><div className="k">In 60 min</div><div className="v num">{top.forecastMargin60C != null ? `${fmt(top.forecastMargin60C)} °C` : "–"}</div></div></div>
            </div>
          </section>
        )}
        <section className="card flush" aria-label="Right now">
          <div className="card-head" style={{ padding: "20px 20px 0", marginBottom: 14 }}><h2>Right now</h2>{top && <Link className="small" to={`/equipment/${top.equipmentId}`}>Details</Link>}</div>
          <div className="mini">
            <div><div className="k">At risk</div><div className={`v num ${atRisk ? "t-warn" : ""}`}>{atRisk}<small>/ {rows.length}</small></div><div className="c">warning or critical</div></div>
            <div><div className="k">Open alerts</div><div className="v num">{active.length}</div><div className="c">{active.filter((a) => a.status === "OPEN").length} not acknowledged</div></div>
            <div><div className="k">Sensors trusted</div><div className="v num">{trusted.length}<small>/ {rows.length}</small></div><div className="c">{rows.length - trusted.length ? `${rows.length - trusted.length} ignored` : "all reporting"}</div></div>
            <div><div className="k">Average humidity</div><div className="v num">{fmt(avgRh, 0)}<small>%</small></div><div className="c">trusted sensors</div></div>
          </div>
        </section>
      </div>

      <div className="section"><div className="sec-head"><h2>Quick access</h2></div>
        <div className="quick">
          <Link to="/alerts"><span className="circ"><Icon name="bell" /></span>Alerts</Link>
          <Link to="/health"><span className="circ"><Icon name="pulse" /></span>Sensors</Link>
          <button onClick={() => { setView("table"); document.getElementById("eq-section")?.scrollIntoView({ behavior: "smooth" }); }}><span className="circ"><Icon name="list" /></span>Equipment</button>
          {can("MANAGER", "ADMIN") && <Link to="/reports"><span className="circ"><Icon name="file" /></span>Reports</Link>}
          {can("MANAGER", "ADMIN") && <Link to="/audit"><span className="circ"><Icon name="shield" /></span>Audit</Link>}
          {can("ADMIN") && <Link to="/admin"><span className="circ"><Icon name="sliders" /></span>Simulator</Link>}
        </div>
      </div>

      <div className="section"><div className="sec-head"><div><h2>Locations</h2><p>Select a location to filter the equipment below</p></div>{loc !== "all" && <button className="link-btn" onClick={() => setLoc("all")}>Show all</button>}</div>
        <div className="locs">
          {locations.map((l) => {
            const es = rows.filter((e) => e.locationName === l);
            const worst = es.map(levelOf).sort((a, b) => levelOrder.indexOf(a) - levelOrder.indexOf(b))[0];
            const risky = es.filter((e) => e.trusted && e.riskLevel !== "NORMAL").length;
            return (
              <button key={l} className="loc" onClick={() => { setLoc(l); document.getElementById("eq-section")?.scrollIntoView({ behavior: "smooth" }); }} style={loc === l ? { borderColor: "var(--accent)", boxShadow: "0 0 0 3px var(--accent-soft)" } : undefined}>
                <div className="art"><Art kind={es[0].profileKind} /><Badge value={worst} text={worst === "UNKNOWN" ? "Check sensor" : label(worst)} /></div>
                <div className="meta"><div><b>{l}</b><span>{es.length} devices, {risky || "none"} at risk</span></div><span className="chev"><Icon name="chevR" /></span></div>
              </button>
            );
          })}
        </div>
      </div>

      <div className="section" id="eq-section">
        <div className="sec-head"><div><h2>Equipment</h2><p>{list.length} of {rows.length} shown</p></div>
          <Segmented label="View" value={view} onChange={setView} items={[{ key: "tiles", label: <><Icon name="grid" />Tiles</> }, { key: "table", label: <><Icon name="list" />Table</> }]} /></div>
        {view === "tiles" ? (
          <>
            <div className="row" style={{ marginBottom: 16 }}>{filters}</div>
            {list.length ? (
              <div className="tiles">
                {[...list].sort((a, b) => (a.trusted ? a.marginC ?? 99 : 999) - (b.trusted ? b.marginC ?? 99 : 999)).map((e) => {
                  const lv = levelOf(e);
                  return (
                    <button key={e.equipmentId} className="tile" onClick={() => navigate(`/equipment/${e.equipmentId}`)}>
                      <div className="top"><div className={`val num ${e.trusted ? `t-${marginTone(e.marginC!, e.marginWarningC, e.marginCriticalC)}` : "muted"}`}>{e.trusted ? <>{fmt(e.marginC)}<small>°C</small></> : "–"}</div>
                        <span className={`tog ${lv === "CRITICAL" ? "crit" : lv === "WARNING" ? "on" : ""}`}><Icon name={lv === "NORMAL" ? "drop" : lv === "UNKNOWN" ? "wifi" : "alert"} /></span></div>
                      <MarginRail dew={e.dewPointC} surface={e.surfaceTempC} warn={e.marginWarningC} crit={e.marginCriticalC} untrusted={!e.trusted} />
                      <div><div className="name">{e.equipmentName}</div><div className="sub">{e.trusted ? `${label(lv)}, ${fmt(e.humidityPct, 0)}% RH` : "Sensor not trusted"} · {e.locationName}</div></div>
                    </button>
                  );
                })}
              </div>
            ) : <div className="card"><Empty title="No equipment matches" text="Clear the filters to see everything." /></div>}
          </>
        ) : (
          <DataTable rows={list.map((e) => ({ ...e, id: e.equipmentId }))} tools={filters} initialSort={{ key: "margin", dir: "asc" }} pageSize={8} onRowClick={(e) => navigate(`/equipment/${e.equipmentId}`)}
            columns={[
              { key: "name", label: "Equipment", sortable: true, sortValue: (e) => e.equipmentName, render: (e) => <><div className="cell-main">{e.equipmentName}</div><div className="cell-sub">{e.locationName}</div></> },
              { key: "rail", label: "Dew point and surface", width: "26%", render: (e) => <MarginRail dew={e.dewPointC} surface={e.surfaceTempC} warn={e.marginWarningC} crit={e.marginCriticalC} untrusted={!e.trusted} /> },
              { key: "margin", label: "Margin", sortable: true, right: true, sortValue: (e) => (e.trusted ? e.marginC ?? 999 : 999), render: (e) => <b className="num">{e.trusted ? `${fmt(e.marginC)} °C` : "–"}</b> },
              { key: "rh", label: "Humidity", sortable: true, right: true, sortValue: (e) => e.humidityPct ?? 0, render: (e) => <span className="num">{e.trusted ? `${fmt(e.humidityPct, 0)}%` : "–"}</span> },
              { key: "f60", label: "In 60 min", sortable: true, right: true, sortValue: (e) => e.forecastMargin60C ?? 999, render: (e) => <span className="num">{e.trusted && e.forecastMargin60C != null ? `${fmt(e.forecastMargin60C)} °C` : "–"}</span> },
              { key: "level", label: "Status", sortable: true, sortValue: (e) => levelOrder.indexOf(levelOf(e)), render: (e) => (e.trusted ? <Badge value={e.riskLevel} /> : <Badge text="No data" />) },
            ]} />
        )}
      </div>
    </>
  );
}
