import { useState } from "react";
import { auditLog, verifyChain } from "../api/endpoints";
import type { ChainVerification } from "../api/types";
import { DataTable, SearchBox } from "../components/DataTable";
import { DateRangePicker, inRange, type Range } from "../components/DateRangePicker";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Button, ErrorState, PageHead, PageSkeleton } from "../components/ui";
import { config } from "../lib/config";
import { dateTime, label } from "../lib/format";
import { useAsync } from "../lib/useAsync";

export function Audit() {
  const log = useAsync(() => auditLog(), [], 15000);
  const [q, setQ] = useState("");
  const [actor, setActor] = useState("all");
  const [range, setRange] = useState<Range | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [result, setResult] = useState<ChainVerification | null>(null);
  const [tampered, setTampered] = useState(false);

  const verify = async () => {
    setResult(null);
    setProgress(0);
    const t = setInterval(() => setProgress((p) => Math.min(92, (p ?? 0) + 6 + Math.random() * 8)), 90);
    try {
      const r = await verifyChain();
      setProgress(100);
      setTimeout(() => { setProgress(null); setResult(r); }, 250);
    } catch (e) {
      setProgress(null);
      setResult({ intact: false, checked: 0, firstBrokenId: null });
      console.error(e);
    } finally { clearInterval(t); }
  };
  const tamper = async (on: boolean) => {
    const { mockTamper } = await import("../mock/server");
    await mockTamper(on);
    setTampered(on); setResult(null); log.reload();
  };

  if (log.loading) return <PageSkeleton table />;
  if (log.error) return <ErrorState error={log.error} onRetry={log.reload} />;
  const entries = log.data ?? [];
  const actors = [...new Set(entries.map((a) => a.actor))].sort();
  const rows = entries.filter((a) => (actor === "all" || a.actor === actor) && inRange(Date.parse(a.occurredAt), range) && (!q || `${a.action} ${a.actor} ${a.entityType} ${a.entityId}`.toLowerCase().includes(q.toLowerCase())));
  return (
    <>
      <PageHead title="Audit log" sub="Each entry stores the hash of the one before it, so changing any past entry breaks every hash after it."
        actions={<Button variant="primary" busy={progress != null} onClick={verify}><Icon name="lock" />Verify chain</Button>} />
      {progress != null && <div className="card" style={{ marginBottom: 20 }}><div className="row between"><b>Verifying {entries.length.toLocaleString()} entries</b><span className="num muted">{Math.round(progress)}%</span></div><div className="bars" style={{ marginTop: 10 }}><i style={{ width: `${progress}%`, background: "var(--accent)", transition: "width .1s" }} /></div></div>}
      {result && progress == null && (
        <div className={`callout ${result.intact ? "ok" : "crit"}`} style={{ marginBottom: 20 }}><Icon name={result.intact ? "check" : "alert"} />
          <div>{result.intact ? <><b>Chain intact.</b> {result.checked.toLocaleString()} entries checked, every hash matches.</> : <><b>Chain broken.</b> {result.firstBrokenId ? `Entry #${result.firstBrokenId} was altered, so every entry after it no longer matches.` : "Verification failed."}</>}</div></div>
      )}
      <DataTable rows={rows} initialSort={{ key: "at", dir: "desc" }}
        tools={<>
          <SearchBox value={q} onChange={setQ} placeholder="Search actions, actors or targets" />
          <Dropdown label="Actor" value={actor} onChange={setActor} options={[{ value: "all", label: "Anyone" }, ...actors.map((a) => ({ value: a, label: a }))]} />
          <DateRangePicker value={range} onChange={setRange} right />
          <span className="spacer" />
          {config.useMocks && <label className="row small muted" style={{ gap: 8 }}><input type="checkbox" className="cb" checked={tampered} onChange={(e) => tamper(e.target.checked)} />Demo: tamper with an entry</label>}
        </>}
        columns={[
          { key: "id", label: "#", sortable: true, width: "80px", sortValue: (a) => a.id, render: (a) => <span className="num muted">{a.id}</span> },
          { key: "action", label: "Action", sortable: true, sortValue: (a) => a.action, render: (a) => <div className="cell-main">{label(a.action)}</div> },
          { key: "actor", label: "Actor", sortable: true, sortValue: (a) => a.actor, render: (a) => <div className="row" style={{ gap: 10, flexWrap: "nowrap" }}><span className="avatar sm" style={{ background: "var(--soft)", color: "var(--text-2)" }}>{a.actor[0]?.toUpperCase()}</span>{a.actor}</div> },
          { key: "target", label: "Target", render: (a) => <span className="small">{a.entityType} {a.entityId}</span> },
          { key: "at", label: "Time", sortable: true, sortValue: (a) => Date.parse(a.occurredAt), render: (a) => <span className="num small">{dateTime(a.occurredAt)}</span> },
          { key: "hash", label: "Hash", render: (a) => <span className="num small muted" style={{ fontFamily: "ui-monospace, Menlo, monospace" }}>{a.hash.slice(0, 12)}</span> },
        ]} />
    </>
  );
}
