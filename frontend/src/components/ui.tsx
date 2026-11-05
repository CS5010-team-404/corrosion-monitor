import { useEffect, type ReactNode } from "react";
import { NotBuiltError } from "../lib/api";
import { label, type Tone } from "../lib/format";
import { Icon } from "./Icon";

const TONE: Record<string, Tone> = { NORMAL: "ok", HEALTHY: "ok", UP: "ok", RESOLVED: "ok", WARNING: "warn", DEGRADED: "warn", ACKNOWLEDGED: "warn", FAILING: "warn", CRITICAL: "crit", OFFLINE: "crit", STUCK: "crit", FAULTY: "crit", OPEN: "crit", DOWN: "crit" };

/** Status is always colour plus text. */
export function Badge({ value, text, tone }: { value?: string; text?: string; tone?: Tone }) {
  const t = tone ?? (value ? TONE[value] : undefined) ?? "muted";
  return <span className={`badge ${t}`}>{text ?? (value ? label(value) : "")}</span>;
}

export function Button({ busy, children, variant, size, className, ...rest }: { busy?: boolean; variant?: "primary" | "dark" | "ghost"; size?: "sm"; children: ReactNode } & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className={`btn ${variant ?? ""} ${size ?? ""} ${className ?? ""}`} disabled={busy || rest.disabled} aria-busy={busy || undefined} {...rest}>
      {busy && <span className="spin" />}
      {children}
    </button>
  );
}

export function Skeleton({ w = "100%", h = 12, r }: { w?: string | number; h?: number; r?: number }) {
  return <div className="sk" style={{ width: w, height: h, borderRadius: r }} />;
}

export function PageSkeleton({ table }: { table?: boolean }) {
  return (
    <div aria-busy="true" aria-label="Loading">
      <Skeleton w={240} h={26} /><div style={{ height: 10 }} /><Skeleton w={380} /><div style={{ height: 30 }} />
      {table ? (
        <div className="card flush">
          <div className="dt-tools"><Skeleton w={260} h={38} /><Skeleton w={120} h={38} /></div>
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} style={{ display: "flex", gap: 20, padding: 16, borderTop: "1px solid var(--line)" }}><Skeleton w="30%" /><Skeleton w="20%" /><Skeleton w="15%" /><Skeleton w="10%" /></div>
          ))}
        </div>
      ) : (
        <>
          <div className="grid-hero"><div className="sk sk-card" style={{ height: 240 }} /><div className="sk sk-card" style={{ height: 240 }} /></div>
          <div style={{ height: 40 }} />
          <div className="tiles">{Array.from({ length: 4 }, (_, i) => <div key={i} className="sk sk-card" />)}</div>
        </>
      )}
    </div>
  );
}

export function Empty({ title, text, icon = "search" }: { title: string; text?: string; icon?: string }) {
  return <div className="empty"><div className="circ"><Icon name={icon} /></div><b>{title}</b>{text}</div>;
}

/** Calm panel for a part of the app whose endpoint a teammate hasn't built yet (404/501). */
export function NotBuilt({ error }: { error: NotBuiltError }) {
  return (
    <div className="card"><div className="empty"><div className="circ"><Icon name="clock" /></div><b>Not built yet</b>{error.message}
      <div className="small" style={{ marginTop: 8 }}>This page fills in once the endpoint is deployed; reload to check again.</div></div></div>
  );
}

/** Shown on every page that is using sample data from the mock fallback (VITE_MOCK_FALLBACK=true). */
export function SampleBadge() {
  return <Badge tone="warn" text="Sample data: this part isn't built yet" />;
}

/** Error state for a page or panel; an endpoint that isn't built yet gets the calm NotBuilt panel instead. */
export function ErrorState({ error, onRetry }: { error: Error; onRetry: () => void }) {
  if (error instanceof NotBuiltError) return <NotBuilt error={error} />;
  return (
    <div className="card"><div className="empty"><div className="circ" style={{ color: "var(--crit)" }}><Icon name="alert" /></div><b>Could not load this page</b>{error.message}
      <div style={{ marginTop: 14 }}><Button size="sm" onClick={onRetry}><Icon name="refresh" />Try again</Button></div></div></div>
  );
}

export function PageHead({ title, sub, actions, crumbs }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode; crumbs?: ReactNode }) {
  return (
    <>
      {crumbs && <nav className="crumbs" aria-label="Breadcrumb">{crumbs}</nav>}
      <div className="page-head"><div><h1>{title}</h1>{sub && <p>{sub}</p>}</div>{actions && <div className="row">{actions}</div>}</div>
    </>
  );
}

export function Tabs<K extends string>({ items, value, onChange }: { items: { key: K; label: string; count?: number }[]; value: K; onChange: (k: K) => void }) {
  return (
    <div className="tabs" role="tablist">
      {items.map((t) => (
        <button key={t.key} role="tab" aria-selected={value === t.key} onClick={() => onChange(t.key)}>{t.label}{t.count != null && <span className="n num">{t.count}</span>}</button>
      ))}
    </div>
  );
}

export function Segmented<K extends string | number>({ items, value, onChange, label: aria }: { items: { key: K; label: ReactNode }[]; value: K; onChange: (k: K) => void; label: string }) {
  return (
    <div className="seg" role="group" aria-label={aria}>
      {items.map((t) => <button key={String(t.key)} aria-pressed={value === t.key} onClick={() => onChange(t.key)}>{t.label}</button>)}
    </div>
  );
}

/** Closes on Escape. Used by drawers and modals. */
export function useEscape(onEscape: () => void) {
  useEffect(() => {
    const on = (e: KeyboardEvent) => e.key === "Escape" && onEscape();
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, [onEscape]);
}

export function Drawer({ title, badges, sub, onClose, children, footer }: { title: string; badges?: ReactNode; sub?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEscape(onClose);
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={title}>
        <div className="drawer-head"><div>{badges && <div className="row" style={{ gap: 8, marginBottom: 10 }}>{badges}</div>}<h2 style={{ fontSize: 18 }}>{title}</h2>{sub && <div className="small muted" style={{ marginTop: 4 }}>{sub}</div>}</div>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button></div>
        <div className="drawer-body stack">{children}</div>
        {footer && <div className="drawer-foot">{footer}</div>}
      </aside>
    </>
  );
}

export function Modal({ title, sub, onClose, children, footer }: { title: string; sub?: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEscape(onClose);
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="mh"><h2 style={{ fontSize: 18 }}>{title}</h2>{sub && <p className="small muted" style={{ margin: "4px 0 0" }}>{sub}</p>}</div>
        <div className="mb stack">{children}</div>
        {footer && <div className="mf">{footer}</div>}
      </div>
    </>
  );
}
