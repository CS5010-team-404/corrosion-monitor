import { useState } from "react";
import { equipmentStatus, reportFile, reportSummary } from "../api/endpoints";
import { DateRangePicker, type Range } from "../components/DateRangePicker";
import { Dropdown } from "../components/Dropdown";
import { Icon } from "../components/Icon";
import { Button, ErrorState, PageHead } from "../components/ui";
import { saveBlob } from "../lib/api";
import { dShort, fmt } from "../lib/format";
import { useToast } from "../lib/toast";
import { useAsync } from "../lib/useAsync";

export function Reports() {
  const toast = useToast();
  const [range, setRange] = useState<Range | null>(null);
  const [loc, setLoc] = useState("all");
  const [applied, setApplied] = useState<{ range: Range | null; loc: string }>({ range: null, loc: "all" });
  const [busy, setBusy] = useState<string | null>(null);
  const eq = useAsync(equipmentStatus, [], 60000);
  const rep = useAsync(() => reportSummary(applied.range?.from ?? null, applied.range?.to ?? null, applied.loc), [applied]);
  const locations = [...new Set((eq.data ?? []).map((e) => e.locationName))];
  const download = async (kind: "csv" | "pdf") => {
    setBusy(kind);
    try {
      const blob = await reportFile(kind, applied.range?.from ?? null, applied.range?.to ?? null, applied.loc);
      saveBlob(blob, `corrosion-watch-report.${kind}`);
      toast(`${kind.toUpperCase()} report downloaded`);
    } catch (e) { toast((e as Error).message, true); } finally { setBusy(null); }
  };
  const label = applied.range ? `${dShort(applied.range.from)} – ${dShort(applied.range.to)}` : "All time";
  const r = rep.data;
  const stat = (k: string, v: React.ReactNode, c: string) => <div><div className="k">{k}</div><div className="v num">{v}</div><div className="c">{c}</div></div>;
  return (
    <>
      <PageHead title="Reports" sub="Conditions, alerts and corrective actions for a period."
        actions={<><Button busy={busy === "csv"} onClick={() => download("csv")}><Icon name="download" />CSV</Button><Button variant="primary" busy={busy === "pdf"} onClick={() => download("pdf")}><Icon name="download" />Export PDF</Button></>} />
      <div className="card" style={{ marginBottom: 24 }}><div className="row">
        <DateRangePicker value={range} onChange={setRange} />
        <Dropdown label="Location" value={loc} onChange={setLoc} options={[{ value: "all", label: "All locations" }, ...locations.map((l) => ({ value: l, label: l }))]} />
        <span style={{ flex: 1 }} />
        <Button variant="dark" busy={rep.loading} onClick={() => setApplied({ range, loc })}>Generate report</Button>
      </div></div>
      {rep.error ? <ErrorState error={rep.error} onRetry={rep.reload} /> : rep.loading || !r ? <div className="card"><div className="center-load"><span className="spin lg" />Building the report…</div></div> : (
        <>
          <div className="card flush" style={{ marginBottom: 24 }}><div className="mini" style={{ gridTemplateColumns: "repeat(4,1fr)" }}>
            {stat("Alerts opened", r.alertsOpened, label)}
            {stat("Mean time to acknowledge", r.meanMinutesToAcknowledge == null ? "–" : <>{fmt(r.meanMinutesToAcknowledge, 0)}<small>min</small></>, "opened to acknowledged")}
            {stat("Corrective actions", r.correctiveActions, "logged by technicians")}
            {stat("Equipment", r.equipment.length, applied.loc === "all" ? "all locations" : applied.loc)}
          </div></div>
          <div className="card">
            <div className="card-head"><div><h2>Time in each risk level</h2><p className="small muted" style={{ margin: "4px 0 0" }}>Share of readings in the period</p></div>
              <div className="legend" style={{ margin: 0 }}><span><i style={{ background: "var(--ok)" }} />Normal</span><span><i style={{ background: "var(--warn)" }} />Warning</span><span><i style={{ background: "var(--crit)" }} />Critical</span></div></div>
            {r.equipment.map((x, i) => (
              <div key={x.equipmentId} style={{ padding: "12px 0", borderTop: i ? "1px solid var(--line)" : undefined }}>
                <div className="row between"><span><b>{x.equipmentName}</b> <span className="small muted">{x.locationName}</span></span><span className="small muted num">{fmt(x.normalPct, 0)}% normal</span></div>
                <div className="bars" style={{ marginTop: 8 }}><i style={{ width: `${x.normalPct}%`, background: "var(--ok)" }} /><i style={{ width: `${x.warningPct}%`, background: "var(--warn)" }} /><i style={{ width: `${x.criticalPct}%`, background: "var(--crit)" }} /></div>
              </div>
            ))}
          </div>
        </>
      )}
    </>
  );
}
