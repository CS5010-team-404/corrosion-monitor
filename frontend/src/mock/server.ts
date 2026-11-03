// The mock backend: answers every endpoint in api/endpoints.ts from the in-browser simulation, with realistic
// latency, so the full UI runs without Java. Enabled with VITE_USE_MOCKS=true (npm run dev:mock).
import { ApiError, setMockHandler } from "../lib/api";
import { setClock } from "../lib/format";
import type * as T from "../api/types";
import { sim } from "./sim.js";

const { S } = sim;
const iso = (t: number | null | undefined) => (t == null ? null : new Date(t).toISOString());
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
let failedLogins = 0;
let actionSeq = 1;

/* eslint-disable @typescript-eslint/no-explicit-any */
const eqById = (id: number) => S.eq.find((e: any) => e.id === id);
const sensorIdOf = (e: any) => S.eq.indexOf(e) + 1;
const eqBySensor = (sensorId: number) => S.eq[sensorId - 1];

function status(e: any): T.EquipmentStatus {
  const r = sim.riskOf(e), h = S.health[e.dev], f = sim.forecast(e), p = e.prof;
  return {
    equipmentId: e.id, equipmentName: e.name, locationName: e.loc, profileName: p.name, profileKind: e.p,
    riskLevel: h.trusted ? r.level : "UNKNOWN", riskIndex: r.idx, temperatureC: e.t, humidityPct: e.rh, surfaceTempC: e.surface,
    dewPointC: r.dew, marginC: r.margin, forecastMargin15C: f ? f.m15 : null, forecastMargin30C: f ? f.m30 : null, forecastMargin60C: f ? f.m60 : null,
    predictedCondensationAt: f && f.toCrit != null && f.toCrit < 60 ? iso(sim.now() + f.toCrit * 60000) : null, updatedAt: iso(h.lastSeen),
    marginWarningC: p.mWarn, marginCriticalC: p.mCrit, rhWarningPct: p.rhWarn, rhCriticalPct: p.rhCrit, tempMinC: p.tMin, tempMaxC: p.tMax, surfaceOffsetC: p.offset,
    sensorId: sensorIdOf(e), deviceId: e.dev, trusted: h.trusted, timeOfWetnessMin24h: e.hist.filter((x: any) => x.rh > 80 && x.t > 0).length,
  };
}

function health(e: any): T.SensorHealth {
  const h = S.health[e.dev];
  const conn = h.issues.includes("OFFLINE") ? "OFFLINE" : "ONLINE";
  return { sensorId: sensorIdOf(e), deviceId: e.dev, kind: "SIMULATED", model: "SIM-SHT31", expectedIntervalSeconds: 15, equipmentId: e.id,
    equipmentName: e.name, locationName: e.loc, status: h.status, connectivity: conn, score: h.score, trusted: h.trusted, issues: h.issues,
    lastSeenAt: iso(h.lastSeen), missingPct: h.missing, signatureFailures: h.issues.includes("SIGNATURE_FAILURES") ? 5 : 0, statusSince: null };
}

function alertView(a: any): T.Alert {
  const e = eqById(a.eqId);
  return { id: a.id, type: a.type, source: a.type.startsWith("SENSOR") ? "SENSOR_HEALTH" : "ANALYTICS", severity: a.sev, status: a.status,
    escalationLevel: a.status === "OPEN" && a.sev === "CRITICAL" && sim.now() - a.opened > 300000 ? 1 : 0, equipmentId: a.eqId,
    equipmentName: e ? e.name : null, locationName: e ? e.loc : null, sensorId: e ? sensorIdOf(e) : null, deviceId: e ? e.dev : null,
    title: a.title, message: a.msg, openedAt: iso(a.opened)!, acknowledgedAt: iso(a.ack), acknowledgedBy: a.ackBy ?? null,
    resolvedAt: iso(a.resolved), resolvedBy: a.resolvedBy ?? null, conditionClearedAt: iso(a.cleared) };
}

const role = () => (sessionStorage.getItem("cw.session") ? JSON.parse(sessionStorage.getItem("cw.session")!).role : "TECHNICIAN");
const actor = () => ({ ADMIN: "admin", MANAGER: "manager", OPERATOR: "operator", TECHNICIAN: "tech" } as Record<string, string>)[role()];

function levels(from: number | null, to: number | null, loc: string | null) {
  return S.eq.filter((e: any) => !loc || e.loc === loc).map((e: any) => {
    const pts = e.hist.filter((x: any) => (!from || x.ts >= from) && (!to || x.ts <= to + 86400000));
    let ok = 0, w = 0, c = 0;
    pts.forEach((x: any) => { const m = x.surface - x.dew; if (m <= e.prof.mCrit || x.rh >= e.prof.rhCrit) c++; else if (m <= e.prof.mWarn || x.rh >= e.prof.rhWarn) w++; else ok++; });
    const n = Math.max(1, pts.length);
    return { equipmentId: e.id, equipmentName: e.name, locationName: e.loc, normalPct: (ok / n) * 100, warningPct: (w / n) * 100, criticalPct: (c / n) * 100 };
  });
}

async function handle(path: string, init: RequestInit): Promise<unknown> {
  await wait(220 + Math.random() * 380);
  const method = (init.method || "GET").toUpperCase();
  const url = new URL(path, "http://mock");
  const p = url.pathname, qp = url.searchParams;
  const body = init.body ? JSON.parse(String(init.body)) : {};
  let m: RegExpMatchArray | null;

  if (p === "/api/auth/login") {
    if (failedLogins >= 5) throw new ApiError(423, "Account locked after 5 failed sign-ins. Try again in 15 minutes.");
    if (body.password === "wrong") { failedLogins++; throw new ApiError(401, `Incorrect username or password. ${5 - failedLogins} attempts left.`); }
    const roles: Record<string, T.Role> = { admin: "ADMIN", manager: "MANAGER", operator: "OPERATOR", tech: "TECHNICIAN" };
    const r = roles[String(body.username).toLowerCase()] ?? "TECHNICIAN";
    sim.addAudit(body.username || "tech", "USER_LOGIN", "user", body.username || "tech");
    return { token: `mock.${r}`, expiresAt: iso(Date.now() + 8 * 3600e3), username: body.username || "tech", fullName: sim.ROLE_NAMES[r], role: r };
  }
  if (p === "/api/equipment/status") return S.eq.map(status);
  if ((m = p.match(/^\/api\/equipment\/(\d+)\/assessments$/))) {
    const e = eqById(+m[1]); const minutes = +(qp.get("minutes") || 120);
    return e.hist.slice(-minutes).map((x: any) => ({ measuredAt: iso(x.ts), temperatureC: x.t, humidityPct: x.rh, surfaceTempC: x.surface, dewPointC: x.dew, marginC: x.surface - x.dew }));
  }
  if ((m = p.match(/^\/api\/sensors\/(\d+)\/forecast$/))) {
    const e = eqBySensor(+m[1]); const f = sim.forecast(e); const r = sim.riskOf(e);
    if (!f) throw new ApiError(404, "No recent readings");
    const crit = e.prof.mCrit;
    return { margin15C: f.m15, margin30C: f.m30, margin60C: f.m60, predictedCondensationAt: f.toCrit != null && f.toCrit < 60 ? iso(sim.now() + f.toCrit * 60000) : null,
      risk30: f.m30 <= crit ? 0.8 : 0.1, risk60: f.m60 <= crit ? 0.75 : 0.12, warn30: r.margin > crit && f.m30 <= crit, warn60: r.margin > crit && f.m60 <= crit, method: "trend", modelVersion: "mock-trend" };
  }
  if (p === "/api/health/sensors") return S.eq.map(health);
  if ((m = p.match(/^\/api\/health\/sensors\/(\d+)$/))) {
    const e = eqBySensor(+m[1]);
    return { sensor: health(e), events: S.events.filter((x: any) => x.dev === e.dev).map(eventView), readings: e.hist.slice(-60).map((x: any) => ({ measuredAt: iso(x.ts), temperatureC: x.t, humidityPct: x.rh })) };
  }
  if (p === "/api/health/events") return S.events.slice(0, +(qp.get("limit") || 50)).map(eventView);
  if (p === "/api/health/services") return S.services.map((s: any) => ({ service: s.name, state: s.state, lastRunAt: iso(sim.now() - 2000), lastError: null, errorCount: 0 }));
  if (p === "/api/sensors") return S.eq.map((e: any) => ({ id: sensorIdOf(e), deviceId: e.dev, kind: "SIMULATED", model: "SIM-SHT31", expectedIntervalS: 15, active: true, equipmentId: e.id, equipmentName: e.name, locationName: e.loc }));
  if (p === "/api/admin/sensors" && method === "POST") {
    sim.addAudit(actor(), "SENSOR_REGISTERED", "sensor", body.deviceId);
    return { deviceId: body.deviceId, secret: Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[b % 64]).join("") };
  }
  if (p === "/api/admin/simulator") return S.eq.map((e: any) => ({ deviceId: e.dev, scenario: e.scenario, intervalSeconds: 15, lastHttpStatus: 202, sent: 0, failed: 0 }));
  if (p === "/api/admin/simulator/scenarios") return sim.SCENARIOS.map(([name, description]: string[]) => ({ name, description }));
  if ((m = p.match(/^\/api\/admin\/simulator\/([^/]+)\/scenario$/))) {
    const e = S.eq.find((x: any) => x.dev === decodeURIComponent(m![1])); e.scenario = body.scenario; e.since = sim.now();
    sim.addAudit(actor(), "SIMULATOR_SCENARIO_SET", "sensor", e.dev); return { deviceId: e.dev, scenario: e.scenario };
  }
  if (p === "/api/alerts") return S.alerts.map(alertView);
  if ((m = p.match(/^\/api\/alerts\/(\d+)$/))) { const a = S.alerts.find((x: any) => x.id === +m![1]); return { alert: alertView(a), actions: a.actions.map(actionView) }; }
  if ((m = p.match(/^\/api\/alerts\/(\d+)\/(acknowledge|resolve|actions)$/))) {
    const a = S.alerts.find((x: any) => x.id === +m![1]); const who = actor();
    if (m[2] === "acknowledge") { if (a.status !== "OPEN") throw new ApiError(409, "Only open alerts can be acknowledged"); a.status = "ACKNOWLEDGED"; a.ack = sim.now(); a.ackBy = who; sim.addAudit(who, "ALERT_ACKNOWLEDGED", "alert", a.id); }
    if (m[2] === "resolve") { a.status = "RESOLVED"; a.resolved = sim.now(); a.resolvedBy = who; if (!a.ack) { a.ack = sim.now(); a.ackBy = who; } if (body.notes) a.actions.push({ type: "Resolution note", notes: body.notes, by: who, at: sim.now() }); sim.addAudit(who, "ALERT_RESOLVED", "alert", a.id); }
    if (m[2] === "actions") { a.actions.push({ type: body.actionType, notes: body.notes, by: who, at: sim.now() }); sim.addAudit(who, "CORRECTIVE_ACTION_LOGGED", "alert", a.id); return actionView(a.actions[a.actions.length - 1]); }
    return alertView(a);
  }
  if (p === "/api/audit") return S.audit.map((a: any) => ({ id: a.id, occurredAt: iso(a.at), actorType: ["alerts", "sensor-health"].includes(a.actor) ? "SYSTEM" : "USER", actor: a.actor, action: a.action, entityType: a.entity, entityId: String(a.entityId), prevHash: a.prev, hash: S.tampered && a === S.audit[12] ? "e" + a.hash.slice(1) : a.hash }));
  if (p === "/api/audit/verify") { await wait(900); return S.tampered ? { intact: false, checked: S.audit.length, firstBrokenId: S.audit[12].id } : { intact: true, checked: S.audit.length, firstBrokenId: null }; }
  if (p === "/api/mock/tamper") { S.tampered = !!body.on; return { tampered: S.tampered }; }
  if (p === "/api/reports/summary" || p === "/api/reports/summary.csv" || p === "/api/reports/summary.pdf") {
    const from = qp.get("from") ? Date.parse(qp.get("from")!) : null, to = qp.get("to") ? Date.parse(qp.get("to")!) : null, loc = qp.get("location");
    const eqIds = new Set(S.eq.filter((e: any) => !loc || e.loc === loc).map((e: any) => e.id));
    const al = S.alerts.filter((a: any) => eqIds.has(a.eqId) && (!from || a.opened >= from) && (!to || a.opened < to + 86400000));
    const acked = al.filter((a: any) => a.ack);
    const summary: T.ReportSummary = { from: iso(from), to: iso(to), alertsOpened: al.length, meanMinutesToAcknowledge: acked.length ? acked.reduce((s: number, a: any) => s + (a.ack - a.opened), 0) / acked.length / 60000 : null,
      correctiveActions: al.reduce((s: number, a: any) => s + a.actions.length, 0), equipment: levels(from, to, loc) };
    if (p.endsWith(".csv")) {
      const rows = ["equipment,location,normal_pct,warning_pct,critical_pct", ...summary.equipment.map((e) => `"${e.equipmentName}","${e.locationName}",${e.normalPct.toFixed(1)},${e.warningPct.toFixed(1)},${e.criticalPct.toFixed(1)}`)];
      return new Blob([rows.join("\n")], { type: "text/csv" });
    }
    if (p.endsWith(".pdf")) throw new ApiError(501, "PDF reports are generated by the backend (OpenPDF). The mock returns CSV only.");
    return summary;
  }
  if (p === "/api/profiles") return Object.values(sim.PROFILES).map((x: any, i: number) => ({ id: i + 1, name: x.name, rhWarningPct: x.rhWarn, rhCriticalPct: x.rhCrit, marginWarningC: x.mWarn, marginCriticalC: x.mCrit, tempMinC: x.tMin, tempMaxC: x.tMax, surfaceOffsetC: x.offset }));
  if (p === "/api/admin/users") return Object.entries(sim.ROLE_NAMES).map(([r, n]: [string, any], i) => ({ id: i + 1, username: ({ ADMIN: "admin", MANAGER: "manager", OPERATOR: "operator", TECHNICIAN: "tech" } as any)[r], fullName: n, email: null, role: r, enabled: true, lockedUntil: null, lastLoginAt: iso(sim.now() - (i + 1) * 3600e3) }));
  throw new ApiError(404, `Mock has no handler for ${method} ${p}`);
}

function eventView(x: any): T.HealthEvent {
  const e = S.eq.find((q: any) => q.dev === x.dev);
  return { id: x.start, sensorId: sensorIdOf(e), deviceId: x.dev, issue: x.issue, severity: x.sev, startedAt: iso(x.start)!, endedAt: iso(x.end), durationSeconds: Math.round(((x.end ?? sim.now()) - x.start) / 1000), details: {} };
}
function actionView(x: any): T.CorrectiveAction {
  return { id: actionSeq++, actionType: x.type, notes: x.notes || null, username: x.by, performedAt: iso(x.at)! };
}

export function installMocks(onAlert: (title: string) => void) {
  setClock(() => sim.now());
  setMockHandler(handle);
  sim.start();
  const quietUntil = Date.now() + 6000;
  sim.onAlert((title: string) => { if (Date.now() > quietUntil) onAlert(title); });
}
export const mockTamper = (on: boolean) => handle("/api/mock/tamper", { method: "POST", body: JSON.stringify({ on }) });
