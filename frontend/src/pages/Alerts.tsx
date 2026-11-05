import { useState } from "react";
import { acknowledge, addAction, alertDetail, alerts as fetchAlerts, resolve } from "../api/endpoints";
import type { Alert, AlertStatus } from "../api/types";
import { DataTable, SearchBox } from "../components/DataTable";
import { DateRangePicker, inRange, type Range } from "../components/DateRangePicker";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Badge, Button, Drawer, ErrorState, PageHead, PageSkeleton, Skeleton, Tabs } from "../components/ui";
import { useAuth } from "../lib/auth";
import { config } from "../lib/config";
import { ago, dateTime, label, now } from "../lib/format";
import { useLive } from "../lib/live";
import { Link, useRouter } from "../lib/router";
import { useToast } from "../lib/toast";
import { useAsync } from "../lib/useAsync";

type Tab = "active" | AlertStatus | "all";
const ACTIONS = ["Inspected equipment", "Turned on dehumidifier", "Closed door or vent", "Moved equipment", "Replaced sensor", "Note"];

export function Alerts() {
  const { path, navigate } = useRouter();
  const params = new URLSearchParams(path.split("?")[1] ?? "");
  const { can } = useAuth();
  const toast = useToast();
  const list = useAsync(() => fetchAlerts("ALL", 500), [], config.useMocks ? 3000 : 15000);
  useLive("/topic/alerts", () => list.reload());
  const [tab, setTab] = useState<Tab>("active");
  const [q, setQ] = useState(params.get("q") ?? "");
  const [sev, setSev] = useState("all");
  const [range, setRange] = useState<Range | null>(null);
  const [busy, setBusy] = useState(false);
  const selected = params.get("alert") ? +params.get("alert")! : null;
  const openAlert = (id: number | null) => navigate(id ? `/alerts?alert=${id}` : "/alerts");

  if (list.loading) return <PageSkeleton table />;
  if (list.error) return <ErrorState error={list.error} onRetry={list.reload} />;
  const all = list.data ?? [];
  const inTab = (a: Alert, t: Tab) => (t === "all" ? true : t === "active" ? a.status !== "RESOLVED" : a.status === t);
  const rows = all.filter((a) => inTab(a, tab) && (sev === "all" || a.severity === sev) && inRange(Date.parse(a.openedAt), range) && (!q || `${a.title} ${a.message ?? ""} ${a.equipmentName ?? ""}`.toLowerCase().includes(q.toLowerCase())));
  const bulkAck = async (ids: (string | number)[], clear: () => void) => {
    setBusy(true);
    let n = 0;
    for (const id of ids) {
      const a = all.find((x) => x.id === id);
      if (a?.status === "OPEN") { try { await acknowledge(a.id); n++; } catch { /* reported below */ } }
    }
    setBusy(false); clear(); list.reload();
    toast(n ? `${n} alert${n > 1 ? "s" : ""} acknowledged` : "Selected alerts were already acknowledged");
  };

  return (
    <>
      <PageHead title="Alerts" sub="Unacknowledged critical alerts escalate to the manager after 5 minutes." actions={<Button size="sm" onClick={async () => { await list.reload(); toast("Alerts up to date"); }}><Icon name="refresh" />Refresh</Button>} />
      <Tabs value={tab} onChange={setTab} items={([["active", "Active"], ["OPEN", "Open"], ["ACKNOWLEDGED", "Acknowledged"], ["RESOLVED", "Resolved"], ["all", "All"]] as [Tab, string][]).map(([key, l]) => ({ key, label: l, count: all.filter((a) => inTab(a, key)).length }))} />
      <DataTable rows={rows} initialSort={{ key: "opened", dir: "desc" }} selectable={can("TECHNICIAN", "ADMIN")} onRowClick={(a) => openAlert(a.id)} isSelected={(a) => a.id === selected}
        bulk={(ids, clear) => <Button size="sm" variant="primary" busy={busy} onClick={() => bulkAck(ids, clear)}><Icon name="check" />Acknowledge</Button>}
        emptyTitle="No alerts here" emptyText="New problems appear here within seconds."
        tools={<>
          <SearchBox value={q} onChange={setQ} placeholder="Search alerts" />
          <Dropdown label="Severity" value={sev} onChange={setSev} options={[{ value: "all", label: "Any severity" }, { value: "CRITICAL", label: "Critical" }, { value: "WARNING", label: "Warning" }]} />
          <DateRangePicker value={range} onChange={setRange} />
          {(sev !== "all" || range || q) && <button className="chipf" onClick={() => { setSev("all"); setRange(null); setQ(""); }}>Clear filters <Icon name="x" /></button>}
        </>}
        columns={[
          { key: "sev", label: "Severity", sortable: true, width: "120px", sortValue: (a) => (a.severity === "CRITICAL" ? 0 : 1), render: (a) => <Badge tone={a.status === "RESOLVED" ? "muted" : a.severity === "CRITICAL" ? "crit" : "warn"} text={label(a.severity)} /> },
          { key: "title", label: "Alert", sortable: true, sortValue: (a) => a.title, render: (a) => <><div className="cell-main">{a.title}</div><div className="cell-sub">{a.message}</div></> },
          { key: "status", label: "Status", sortable: true, sortValue: (a) => ["OPEN", "ACKNOWLEDGED", "RESOLVED"].indexOf(a.status), render: (a) => <Badge value={a.status} /> },
          { key: "opened", label: "Opened", sortable: true, right: true, sortValue: (a) => Date.parse(a.openedAt), render: (a) => <><div className="num">{dateTime(a.openedAt)}</div><div className="cell-sub num">{ago(a.openedAt)}</div></> },
        ]} />
      {selected && <AlertDrawer id={selected} onClose={() => openAlert(null)} onChanged={list.reload} />}
    </>
  );
}

function AlertDrawer({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const { can } = useAuth();
  const toast = useToast();
  const d = useAsync(() => alertDetail(id), [id], config.useMocks ? 3000 : 10000);
  const [busy, setBusy] = useState<string | null>(null);
  const [type, setType] = useState(ACTIONS[0]);
  const [notes, setNotes] = useState("");
  const run = async (key: string, fn: () => Promise<unknown>, done: string) => {
    setBusy(key);
    try { await fn(); toast(done); d.reload(); onChanged(); } catch (e) { toast((e as Error).message, true); } finally { setBusy(null); }
  };
  if (d.loading || !d.data) return <Drawer title="Loading alert" onClose={onClose}><Skeleton h={20} w="70%" /><Skeleton h={14} /><Skeleton h={120} /></Drawer>;
  const { alert: a, actions } = d.data;
  const steps: [string, string][] = [[a.openedAt, `Opened as ${a.severity.toLowerCase()} by ${a.source === "SENSOR_HEALTH" ? "sensor health" : "analytics"}`]];
  if (a.acknowledgedAt) steps.push([a.acknowledgedAt, `Acknowledged by ${a.acknowledgedBy}`]);
  actions.forEach((x) => steps.push([x.performedAt, `${x.actionType} by ${x.username}${x.notes ? `: ${x.notes}` : ""}`]));
  if (a.conditionClearedAt) steps.push([a.conditionClearedAt, "Condition cleared"]);
  if (a.resolvedAt) steps.push([a.resolvedAt, `Resolved by ${a.resolvedBy}`]);
  steps.sort((x, y) => Date.parse(x[0]) - Date.parse(y[0]));
  const escIn = a.status === "OPEN" && a.severity === "CRITICAL" ? Math.max(0, 5 - Math.floor((now() - Date.parse(a.openedAt)) / 60000)) : null;
  const tech = can("TECHNICIAN", "ADMIN") && a.status !== "RESOLVED";
  return (
    <Drawer title={a.title} onClose={onClose} badges={<><Badge value={a.severity} /><Badge value={a.status} /></>} sub={`${a.locationName ? `${a.locationName}, ` : ""}opened ${dateTime(a.openedAt)}`}
      footer={tech ? <>
        {a.status === "OPEN" && <Button variant="primary" busy={busy === "ack"} onClick={() => run("ack", () => acknowledge(a.id), "Alert acknowledged")}><Icon name="check" />Acknowledge</Button>}
        <Button busy={busy === "res"} onClick={() => run("res", () => resolve(a.id), "Alert resolved")}>Resolve</Button>
      </> : undefined}>
      <p style={{ margin: 0, color: "var(--text-2)" }}>{a.message}</p>
      {escIn != null && <div className="callout crit"><Icon name="alert" /><div>{escIn ? <>Escalates to the manager in <b>{escIn} min</b> unless acknowledged.</> : "Escalated to the manager."}</div></div>}
      <div><h3 style={{ margin: "8px 0 14px" }}>Timeline</h3>
        <ol className="timeline">{steps.map(([t, s], i) => <li key={i} className={i === steps.length - 1 ? "now" : ""}><div className="when num">{dateTime(t)}</div>{s}</li>)}</ol></div>
      {tech && (
        <div className="stack" style={{ borderTop: "1px solid var(--line)", paddingTop: 18 }}>
          <div className="field">Corrective action<Dropdown block value={type} onChange={setType} options={ACTIONS.map((x) => ({ value: x, label: x }))} /></div>
          <label className="field">Notes<textarea value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="What did you do and what did you see?" /></label>
          <div><Button size="sm" busy={busy === "log"} onClick={() => run("log", async () => { await addAction(a.id, type, notes); setNotes(""); }, "Action logged")}>Log action</Button></div>
        </div>
      )}
      {!tech && a.status !== "RESOLVED" && <p className="small muted">Technicians and admins act on alerts.</p>}
      {a.equipmentId && <Link className="small" to={`/equipment/${a.equipmentId}`}>Open {a.equipmentName}</Link>}
    </Drawer>
  );
}
