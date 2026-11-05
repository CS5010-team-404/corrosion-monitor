import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { alerts as fetchAlerts, equipmentStatus } from "../api/endpoints";
import type { Role } from "../api/types";
import { Icon } from "../components/Icon";
import { Menu, useOutside } from "../components/Dropdown";
import { SampleBadge } from "../components/ui";
import { isNotBuilt, sampleSources } from "../lib/api";
import { useAuth } from "../lib/auth";
import { config } from "../lib/config";
import { ago, label, time, now } from "../lib/format";
import { Link, useRouter } from "../lib/router";
import { useAsync, usePageUsesSample } from "../lib/useAsync";
import { useLive } from "../lib/live";

const ALL: Role[] = ["TECHNICIAN", "OPERATOR", "MANAGER", "ADMIN"];
export const NAV: { to: string; label: string; icon: string; roles: Role[]; group: "ops" | "manage" }[] = [
  { to: "/", label: "Overview", icon: "home", roles: ALL, group: "ops" },
  { to: "/alerts", label: "Alerts", icon: "bell", roles: ALL, group: "ops" },
  { to: "/health", label: "Sensor health", icon: "pulse", roles: ALL, group: "ops" },
  { to: "/reports", label: "Reports", icon: "file", roles: ["MANAGER", "ADMIN"], group: "manage" },
  { to: "/audit", label: "Audit log", icon: "shield", roles: ["MANAGER", "ADMIN"], group: "manage" },
  { to: "/admin", label: "Administration", icon: "sliders", roles: ["ADMIN"], group: "manage" },
];
const FIRST: Record<Role, string> = { TECHNICIAN: "Tariq", OPERATOR: "Olivia", MANAGER: "Morgan", ADMIN: "Avery" };

/** Thin progress bar at the top whenever the route changes. */
function RouteProgress({ path }: { path: string }) {
  const [state, setState] = useState("");
  useEffect(() => {
    setState("on");
    const a = setTimeout(() => setState("done"), 450), b = setTimeout(() => setState(""), 900);
    return () => { clearTimeout(a); clearTimeout(b); };
  }, [path]);
  return <div className={`progress ${state}`} />;
}

export function Shell({ children }: { children: ReactNode }) {
  const { session, logout, switchRole } = useAuth();
  const { path, navigate } = useRouter();
  const role = session!.role;
  // Layout queries ("ambient"): they feed the alerts badge and search, so they don't mark the page as sample data.
  const alertsQ = useAsync(() => fetchAlerts("ACTIVE", 50), [], config.useMocks ? 4000 : 15000, { ambient: true });
  const eqQ = useAsync(equipmentStatus, [], 30000, { ambient: true });
  useLive("/topic/alerts", () => alertsQ.reload());
  const alertsMissing = isNotBuilt(alertsQ.error);  // alerts endpoint not built: no badge, no "all caught up"
  const sampleOnPage = usePageUsesSample();
  const samples = useSyncExternalStore(sampleSources.subscribe, sampleSources.get);
  const active = (alertsQ.data ?? []).filter((a) => a.status !== "RESOLVED");
  const open = active.filter((a) => a.status === "OPEN");
  const items = NAV.filter((n) => n.roles.includes(role));
  const current = (to: string) => (to === "/" ? path === "/" || path.startsWith("/equipment") : path.startsWith(to));
  const link = (n: (typeof NAV)[number]) => (
    <Link key={n.to} to={n.to} aria-current={current(n.to) ? "page" : undefined} title={n.label}>
      <Icon name={n.icon} /><span className="lbl">{n.label}</span>{n.to === "/alerts" && open.length > 0 && <span className="count num">{open.length}</span>}
    </Link>
  );

  // global search over equipment and sensors
  const [q, setQ] = useState("");
  const searchRef = useRef<HTMLDivElement>(null);
  useOutside(searchRef, () => setQ(""), !!q);
  const hits = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s) return [];
    const eq = eqQ.data ?? [];
    return [
      ...eq.filter((e) => (e.equipmentName + e.locationName).toLowerCase().includes(s)).map((e) => ({ key: `e${e.equipmentId}`, title: e.equipmentName, sub: `Equipment, ${e.locationName}`, icon: "layers", to: `/equipment/${e.equipmentId}` })),
      ...eq.filter((e) => e.deviceId && e.deviceId.toLowerCase().includes(s)).map((e) => ({ key: `s${e.sensorId}`, title: e.deviceId!, sub: `Sensor on ${e.equipmentName}`, icon: "wifi", to: `/health?sensor=${e.sensorId}` })),
    ].slice(0, 7);
  }, [q, eqQ.data]);
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key === "/" && !["INPUT", "TEXTAREA"].includes((document.activeElement as HTMLElement)?.tagName)) {
        e.preventDefault();
        searchRef.current?.querySelector("input")?.focus();
      }
    };
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, []);

  return (
    <div className="shell">
      <RouteProgress path={path} />
      <aside className="side" aria-label="Navigation">
        <div className="brand"><span className="mark"><Icon name="drop" /></span><span className="word">Corrosion Watch</span></div>
        <nav className="nav">
          {items.filter((n) => n.group === "ops").map(link)}
          {items.some((n) => n.group === "manage") && <div className="nav-label">Manage</div>}
          {items.filter((n) => n.group === "manage").map(link)}
        </nav>
        <div className="side-foot">
          {samples.length > 0 ? (
            <div className="status-card sample"><div className="row1">Sample data in use</div><p>Not built yet, shown with sample data: {samples.map((x) => x.what.replace(/^The /, "").toLowerCase()).join(", ")}.</p></div>
          ) : (
            <div className="status-card"><div className="row1">{config.useMocks ? "Demo data" : "Connected"}</div><p>{config.useMocks ? "Running on the built-in mock backend." : `Last sync ${time(now())}.`}</p></div>
          )}
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="m-brand"><span className="mark"><Icon name="drop" /></span>Corrosion Watch</div>
          <div className="search" ref={searchRef}>
            <Icon name="search" /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search equipment or sensors" aria-label="Search" /><kbd>/</kbd>
            {q && <div className="pop" style={{ width: "100%" }}>
              {hits.length ? hits.map((h) => <button key={h.key} className="opt" onClick={() => { setQ(""); navigate(h.to); }}><span className="ck" style={{ color: "var(--text-3)" }}><Icon name={h.icon} /></span><span>{h.title}<span className="desc">{h.sub}</span></span></button>)
                : <div className="opt muted" style={{ cursor: "default" }}>No matches for “{q}”</div>}
            </div>}
          </div>
          <div className="grow" />
          <span className="live num"><i />Live {time(now())}</span>
          <Menu width={340} trigger={(toggle) => <button className="icon-btn" onClick={toggle} aria-label="Notifications"><Icon name="bell" />{open.length > 0 && <span className="dot" />}</button>}>
            {(close) => (
              <>
                <div className="row between" style={{ padding: "8px 10px 6px" }}><b>Notifications</b>{!alertsMissing && <span className="small muted">{open.length} open</span>}</div>
                {active.slice(0, 5).map((a) => (
                  <button key={a.id} className="opt" style={{ whiteSpace: "normal" }} onClick={() => { close(); navigate(`/alerts?alert=${a.id}`); }}>
                    <span className="ck"><Icon name={a.severity === "CRITICAL" ? "alert" : "info"} /></span><span>{a.title}<span className="desc">{ago(a.openedAt)}, {label(a.status).toLowerCase()}</span></span>
                  </button>
                ))}
                {!active.length && <div className="opt muted" style={{ cursor: "default", whiteSpace: "normal" }}>{alertsMissing ? "Alerts aren't available yet." : "You are all caught up."}</div>}
                <hr /><button className="opt" style={{ justifyContent: "center", color: "var(--accent)", fontWeight: 600 }} onClick={() => { close(); navigate("/alerts"); }}>View all alerts</button>
              </>
            )}
          </Menu>
          <Menu width={250} trigger={(toggle) => (
            <button className="user-btn" onClick={toggle} aria-label="Account"><span className="avatar">{session!.fullName[0]}</span><span className="who"><b>{session!.fullName}</b><span>{label(role)}</span></span></button>
          )}>
            {(close) => (
              <>
                <div style={{ padding: "8px 10px" }}><b>{session!.fullName}</b><div className="small muted">{session!.username}</div></div>
                {switchRole && <><hr /><div className="small muted" style={{ padding: "4px 10px" }}>Demo: view as</div>
                  {ALL.map((r) => <button key={r} className="opt" onClick={() => { switchRole(r); close(); navigate("/"); }}><span className="ck">{r === role && <Icon name="check" />}</span>{label(r)}</button>)}</>}
                <hr /><button className="opt" onClick={() => { close(); logout(); }}><span className="ck"><Icon name="out" /></span>Sign out</button>
              </>
            )}
          </Menu>
        </header>
        <div className="content">{sampleOnPage && <div className="sample-bar"><SampleBadge /></div>}{children}</div>
      </div>
      <nav className="tabbar" aria-label="Main">
        {items.slice(0, 5).map((n) => <Link key={n.to} to={n.to} aria-label={n.label} aria-current={current(n.to) ? "page" : undefined}><Icon name={n.icon} />{n.to === "/alerts" && open.length > 0 && !current(n.to) && <span className="count" />}</Link>)}
      </nav>
    </div>
  );
}

export { FIRST };
