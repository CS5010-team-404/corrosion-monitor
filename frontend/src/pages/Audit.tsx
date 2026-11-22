import { useRef, useState } from "react";
import { AUDIT_MAX_PAGE, auditPage, verifyChain } from "../api/endpoints";
import type { AuditPage, ChainVerification } from "../api/types";
import { DataTable, SearchBox, useTableSize } from "../components/DataTable";
import { DateRangePicker, inRange, type Range } from "../components/DateRangePicker";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Button, ErrorState, PageHead, PageSkeleton } from "../components/ui";
import { errorMessage } from "../lib/api";
import { config } from "../lib/config";
import { dateTime, label } from "../lib/format";
import { useAsync } from "../lib/useAsync";

const DAY = 86_400_000;

/**
 * Audit log. With the agreed paged response ({ items, page, size, total }) the server pages: page, size, actor and
 * date range go in the query, and the search box filters the loaded page. With the older plain array the page asks for
 * the backend's maximum (200, newest first, still filtered by actor and dates on the server) and pages locally.
 */
export function Audit() {
  const [q, setQ] = useState("");
  const [actor, setActor] = useState("all");
  const [range, setRange] = useState<Range | null>(null);
  const [page, setPage] = useState(1);
  const [size, setSize] = useTableSize("audit", 10);
  const [paged, setPaged] = useState<boolean | null>(null);   // unknown until the first response
  const filters = { actor: actor === "all" ? null : actor, from: range ? new Date(range.from).toISOString() : null, to: range ? new Date(range.to + DAY).toISOString() : null };
  const log = useAsync(async () => {
    const r = await auditPage(paged === false ? { page: 0, size: AUDIT_MAX_PAGE, ...filters } : { page: page - 1, size, ...filters });
    if (r.paged !== paged) setPaged(r.paged);
    return r;
  }, [paged, paged ? page : 0, paged ? size : 0, actor, range?.from, range?.to], 15000);
  // Keep showing the last page while the next one loads, instead of flashing the skeleton.
  const last = useRef<(AuditPage & { paged: boolean }) | undefined>(undefined);
  if (log.data) last.current = log.data;
  const shown = log.data ?? last.current;
  const setFilter = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(1); };
  const [progress, setProgress] = useState<number | null>(null);
  const [result, setResult] = useState<ChainVerification | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [tampered, setTampered] = useState(false);

  const verify = async () => {
    setResult(null);
    setVerifyError(null);
    setProgress(0);
    const t = setInterval(() => setProgress((p) => Math.min(92, (p ?? 0) + 6 + Math.random() * 8)), 90);
    try {
      const r = await verifyChain();
      setProgress(100);
      setTimeout(() => { setProgress(null); setResult(r); }, 250);
    } catch (e) {
      // A refusal (403 for a manager) or a failure is not a broken chain: show the server's message as it is.
      setProgress(null);
      setVerifyError(errorMessage(e, "Verification failed. Please try again."));
    } finally { clearInterval(t); }
  };
  const tamper = async (on: boolean) => {
    const { mockTamper } = await import("../mock/server");
    await mockTamper(on);
    setTampered(on); setResult(null); log.reload();
  };

  if (!shown && log.error) return <ErrorState error={log.error} onRetry={log.reload} />;
  if (!shown) return <PageSkeleton table />;
  const entries = shown.items;
  const server = shown.paged;
  const actors = [...new Set([...entries.map((a) => a.actor), ...(actor === "all" ? [] : [actor])])].sort();
  const rows = entries.filter((a) => (actor === "all" || a.actor === actor) && inRange(Date.parse(a.occurredAt), range) && (!q || `${a.action} ${a.actor} ${a.entityType ?? ""} ${a.entityId ?? ""}`.toLowerCase().includes(q.toLowerCase())));
  return (
    <>
      <PageHead title="Audit log" sub="Each entry stores the hash of the one before it, so changing any past entry breaks every hash after it."
        actions={<Button variant="primary" busy={progress != null} onClick={verify}><Icon name="lock" />Verify chain</Button>} />
      {progress != null && <div className="card" style={{ marginBottom: 20 }}><div className="row between"><b>Verifying {(server ? shown.total : entries.length).toLocaleString()} entries</b><span className="num muted">{Math.round(progress)}%</span></div><div className="bars" style={{ marginTop: 10 }}><i style={{ width: `${progress}%`, background: "var(--accent)", transition: "width .1s" }} /></div></div>}
      {verifyError && progress == null && <div className="callout" role="alert" style={{ marginBottom: 20 }}><Icon name="alert" /><div><b>Couldn't verify the chain.</b> {verifyError}</div></div>}
      {result && progress == null && (
        <div className={`callout ${result.intact ? "ok" : "crit"}`} style={{ marginBottom: 20 }}><Icon name={result.intact ? "check" : "alert"} />
          <div>{result.intact ? <><b>Chain intact.</b> {result.checked.toLocaleString()} entries checked, every hash matches.</> : <><b>Chain broken.</b> {result.firstBrokenId ? `Entry #${result.firstBrokenId} was altered, so every entry after it no longer matches.` : result.message || "Verification failed."}</>}</div></div>
      )}
      {log.error && <div className="callout crit" role="alert" style={{ marginBottom: 20 }}><Icon name="alert" /><div><b>Couldn't refresh the audit log.</b> {errorMessage(log.error)}</div></div>}
      <DataTable rows={rows} initialSort={{ key: "at", dir: "desc" }} tableId="audit"
        server={server ? { total: shown.total, page, size, onPage: setPage, onSize: setSize } : undefined}
        tools={<>
          <SearchBox value={q} onChange={setQ} placeholder={server ? "Search this page" : "Search actions, actors or targets"} />
          <Dropdown label="Actor" value={actor} onChange={setFilter(setActor)} options={[{ value: "all", label: "Anyone" }, ...actors.map((a) => ({ value: a, label: a }))]} />
          <DateRangePicker value={range} onChange={setFilter(setRange)} right />
          <span className="spacer" />
          {config.useMocks && <label className="row small muted" style={{ gap: 8 }}><input type="checkbox" className="cb" checked={tampered} onChange={(e) => tamper(e.target.checked)} />Demo: tamper with an entry</label>}
        </>}
        columns={[
          { key: "id", label: "#", sortable: true, width: "80px", sortValue: (a) => a.id, render: (a) => <span className="num muted">{a.id}</span> },
          { key: "action", label: "Action", sortable: true, sortValue: (a) => a.action, render: (a) => <div className="cell-main">{label(a.action)}</div> },
          { key: "actor", label: "Actor", sortable: true, sortValue: (a) => a.actor, render: (a) => <div className="row" style={{ gap: 10, flexWrap: "nowrap" }}><span className="avatar sm" style={{ background: "var(--soft)", color: "var(--text-2)" }}>{a.actor[0]?.toUpperCase()}</span>{a.actor}</div> },
          { key: "target", label: "Target", render: (a) => <span className="small">{a.entityType || a.entityId ? `${a.entityType ?? ""} ${a.entityId ?? ""}`.trim() : "–"}</span> },
          { key: "at", label: "Time", sortable: true, sortValue: (a) => Date.parse(a.occurredAt), render: (a) => <span className="num small">{dateTime(a.occurredAt)}</span> },
          { key: "hash", label: "Hash", render: (a) => <span className="num small muted" style={{ fontFamily: "ui-monospace, Menlo, monospace" }}>{a.hash ? a.hash.slice(0, 12) : "–"}</span> },
        ]} />
    </>
  );
}
