import { useRef, useState } from "react";
import { DAY, dShort, now, startOfDay } from "../lib/format";
import { useOutside } from "./Dropdown";
import { Icon } from "./Icon";

export interface Range { from: number; to: number }
const PRESETS: [string, number][] = [["Today", 0], ["Last 7 days", 6], ["Last 14 days", 13], ["Last 30 days", 29]];

/** Date range picker with presets, a month calendar and explicit Apply, matching the design. */
export function DateRangePicker({ value, onChange, right }: { value: Range | null; onChange: (r: Range | null) => void; right?: boolean }) {
  const [open, setOpen] = useState(false);
  const [month, setMonth] = useState(() => startOfDay(value ? value.to : now()));
  const [s, setS] = useState<number | null>(value?.from ?? null);
  const [e, setE] = useState<number | null>(value?.to ?? null);
  const ref = useRef<HTMLDivElement>(null);
  useOutside(ref, () => setOpen(false), open);

  const toggle = () => {
    if (!open) { setS(value?.from ?? null); setE(value?.to ?? null); setMonth(startOfDay(value ? value.to : now())); }
    setOpen(!open);
  };
  const m = new Date(month), y = m.getFullYear(), mo = m.getMonth();
  const first = new Date(y, mo, 1), start = new Date(y, mo, 1 - ((first.getDay() + 6) % 7));
  const today = startOfDay(now());
  const days = Array.from({ length: 42 }, (_, i) => { const d = new Date(start); d.setDate(start.getDate() + i); return d; });
  const pick = (t: number) => {
    if (!s || e) { setS(t); setE(null); } else if (t < s) { setE(s); setS(t); } else setE(t);
  };
  const shift = (n: number) => { const d = new Date(month); d.setMonth(d.getMonth() + n); setMonth(d.getTime()); };

  return (
    <div className={`dd ${open ? "open" : ""}`} ref={ref}>
      <button type="button" className="dd-btn" onClick={toggle} aria-expanded={open}><Icon name="cal" /><span className="num">{value ? `${dShort(value.from)} – ${dShort(value.to)}` : "Any date"}</span></button>
      {open && (
        <div className={`pop dp dp-pop ${right ? "right" : ""}`} role="dialog" aria-label="Choose dates">
          <div className="presets">
            {PRESETS.map(([l, n]) => <button key={l} type="button" className="opt" onClick={() => { setS(today - n * DAY); setE(today); setMonth(today); }}>{l}</button>)}
            <hr /><button type="button" className="opt" onClick={() => { onChange(null); setOpen(false); }}>Any date</button>
          </div>
          <div className="cal">
            <div className="cal-head">
              <button type="button" className="icon-btn" style={{ width: 34, height: 34 }} onClick={() => shift(-1)} aria-label="Previous month"><Icon name="chevL" /></button>
              <span>{m.toLocaleDateString([], { month: "long", year: "numeric" })}</span>
              <button type="button" className="icon-btn" style={{ width: 34, height: 34 }} onClick={() => shift(1)} aria-label="Next month"><Icon name="chevR" /></button>
            </div>
            <div className="grid7">
              {["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"].map((d) => <div key={d} className="dow">{d}</div>)}
              {days.map((d) => {
                const t = d.getTime(), edge = t === s || t === e, inr = s != null && e != null && t > s && t < e;
                return <button key={t} type="button" disabled={t > today} onClick={() => pick(t)}
                  className={`day ${d.getMonth() !== mo ? "out" : ""} ${edge ? "edge" : ""} ${inr ? "in" : ""} ${t === today ? "today" : ""}`}>{d.getDate()}</button>;
              })}
            </div>
          </div>
          <div className="foot">
            <span className="num">{s ? `${dShort(s)} – ${e ? dShort(e) : "pick an end date"}` : "Pick a start date"}</span>
            <div className="row">
              <button type="button" className="btn sm ghost" onClick={() => setOpen(false)}>Cancel</button>
              <button type="button" className="btn sm primary" disabled={!s || !e} onClick={() => { onChange({ from: s!, to: e! }); setOpen(false); }}>Apply</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export const inRange = (t: number, r: Range | null) => !r || (t >= r.from && t < r.to + DAY);
