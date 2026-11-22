import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import { Icon } from "./Icon";

export interface Option<V extends string> { value: V; label: string; description?: string }

/**
 * Where an open list goes: below the button, or above it when there isn't room below. The list is rendered in
 * document.body (a portal) with fixed coordinates, so a parent with overflow: hidden (e.g. .card.flush around every
 * DataTable) can never clip it. Recomputed on scroll and resize while open.
 */
function usePopPosition(anchor: RefObject<HTMLElement>, pop: RefObject<HTMLElement>, open: boolean, alignRight: boolean) {
  const [style, setStyle] = useState<CSSProperties>({ position: "fixed", visibility: "hidden" });
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const a = anchor.current?.getBoundingClientRect();
      const h = pop.current?.offsetHeight ?? 0;
      if (!a) return;
      const gap = 6, below = window.innerHeight - a.bottom - gap, above = a.top - gap;
      const up = h > below && above > below;
      const s: CSSProperties = { position: "fixed", minWidth: a.width, margin: 0, visibility: "visible" };
      if (up) { s.bottom = window.innerHeight - a.top + gap; s.top = "auto"; } else { s.top = a.bottom + gap; }
      if (alignRight) { s.right = window.innerWidth - a.right; s.left = "auto"; } else { s.left = a.left; }
      setStyle(s);
    };
    place();
    window.addEventListener("scroll", place, true);
    window.addEventListener("resize", place);
    return () => { window.removeEventListener("scroll", place, true); window.removeEventListener("resize", place); };
  }, [open, anchor, pop, alignRight]);
  return style;
}

/** Custom select: same look as the rest of the UI, keyboard accessible (arrows, Enter, Escape). */
export function Dropdown<V extends string>({ value, options, onChange, label, block, right, trigger, ariaLabel }: {
  value: V; options: Option<V>[]; onChange: (v: V) => void; label?: string; block?: boolean; right?: boolean; trigger?: ReactNode; ariaLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const popRef = useRef<HTMLDivElement>(null);
  const current = options.find((o) => o.value === value) ?? options[0];
  useOutside(ref, () => setOpen(false), open, popRef);
  const popStyle = usePopPosition(ref, popRef, open, !!right);
  useEffect(() => { if (open) setActive(Math.max(0, options.findIndex((o) => o.value === value))); }, [open, options, value]);
  const key = (e: KeyboardEvent) => {
    if (!open && ["ArrowDown", "Enter", " "].includes(e.key)) { e.preventDefault(); setOpen(true); return; }
    if (!open) return;
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(options.length - 1, a + 1)); }
    if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(0, a - 1)); }
    if (e.key === "Enter") { e.preventDefault(); onChange(options[active].value); setOpen(false); }
    if (e.key === "Escape") setOpen(false);
  };
  return (
    <div className={`dd ${block ? "block" : ""} ${open ? "open" : ""}`} ref={ref} onKeyDown={key}>
      {trigger ? <span onClick={() => setOpen((o) => !o)}>{trigger}</span> : (
        <button className="dd-btn" type="button" aria-haspopup="listbox" aria-expanded={open} aria-label={ariaLabel} onClick={() => setOpen((o) => !o)}>
          {label && <span className="lbl">{label}</span>}<span>{current?.label}</span><Icon name="chevD" className="chev-d" />
        </button>
      )}
      {open && createPortal(
        <div ref={popRef} className="pop floating" role="listbox" aria-label={ariaLabel ?? label} style={popStyle}>
          {options.map((o, i) => (
            <button key={o.value} type="button" className="opt" role="option" aria-selected={o.value === value} style={i === active ? { background: "var(--soft)" } : undefined}
              onMouseEnter={() => setActive(i)} onClick={() => { onChange(o.value); setOpen(false); }}>
              <span className="ck">{o.value === value && <Icon name="check" />}</span>
              <span>{o.label}{o.description && <span className="desc">{o.description}</span>}</span>
            </button>
          ))}
        </div>,
        document.body,
      )}
    </div>
  );
}

/** Popover menu with arbitrary content (notifications, account). */
export function Menu({ trigger, children, width = 260 }: { trigger: (toggle: () => void, open: boolean) => ReactNode; children: (close: () => void) => ReactNode; width?: number }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useOutside(ref, () => setOpen(false), open);
  return (
    <div className="dd" ref={ref}>
      {trigger(() => setOpen((o) => !o), open)}
      {open && <div className="pop right" style={{ width }}>{children(() => setOpen(false))}</div>}
    </div>
  );
}

/** Calls onOutside on a mouse press outside ref (and outside `also`, e.g. a list rendered in a portal) or on Escape. */
export function useOutside(ref: React.RefObject<HTMLElement>, onOutside: () => void, active: boolean, also?: React.RefObject<HTMLElement>) {
  useEffect(() => {
    if (!active) return;
    const on = (e: MouseEvent) => {
      const t = e.target as Node;
      if (ref.current && !ref.current.contains(t) && !also?.current?.contains(t)) onOutside();
    };
    const esc = (e: globalThis.KeyboardEvent) => e.key === "Escape" && onOutside();
    document.addEventListener("mousedown", on);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", on); document.removeEventListener("keydown", esc); };
  }, [ref, onOutside, active, also]);
}
