/* eslint-disable */
// @ts-nocheck
// Mock backend simulation (VITE_USE_MOCKS=true). Same behaviour as docs/ui-design.html.
const listeners = new Set();
const alertListeners = new Set();
// ------------------------------------------------------------------------------------------------
// Data model. Mirrors the real API (docs/ARCHITECTURE.md). Time runs 30x faster than real.
// ------------------------------------------------------------------------------------------------
const SPEED = 10; // simulated seconds per real second (mock mode only)

const dewPoint = (t, rh) => { const a = 17.62, b = 243.12; const g = Math.log(rh / 100) + a * t / (b + t); return b * g / (a - g); };
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const fmt = (v, d = 1) => (v == null || Number.isNaN(v)) ? "–" : v.toFixed(d);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

const PROFILES = {
  it:    { name: "IT equipment (ASHRAE)", rhWarn: 60, rhCrit: 70, mWarn: 3.0, mCrit: 1.0, tMin: 18, tMax: 27, offset: 1.5 },
  tel:   { name: "Telecom cabinet", rhWarn: 60, rhCrit: 75, mWarn: 3.0, mCrit: 1.0, tMin: 5, tMax: 40, offset: 2.0 },
  plc:   { name: "Industrial control panel", rhWarn: 65, rhCrit: 80, mWarn: 4.0, mCrit: 1.5, tMin: 0, tMax: 45, offset: 2.5 },
  out:   { name: "Outdoor enclosure", rhWarn: 70, rhCrit: 85, mWarn: 4.0, mCrit: 1.5, tMin: -10, tMax: 50, offset: 3.0 },
};
const EQUIPMENT = [
  { id: 1, name: "Rack A1", loc: "Server Room A", p: "it", t: 22.4, rh: 45, dev: "SIM-001" },
  { id: 2, name: "Rack A2", loc: "Server Room A", p: "it", t: 19.6, rh: 58, dev: "SIM-002", drift: true },
  { id: 3, name: "Rack A3", loc: "Server Room A", p: "it", t: 21.8, rh: 47, dev: "SIM-003" },
  { id: 4, name: "Core switch cabinet", loc: "Telecom Closet 2", p: "tel", t: 24.1, rh: 49, dev: "SIM-004" },
  { id: 5, name: "Patch panel C2", loc: "Telecom Closet 2", p: "tel", t: 23.2, rh: 52, dev: "SIM-005" },
  { id: 6, name: "Router cabinet", loc: "Telecom Closet 2", p: "tel", t: 25.0, rh: 46, dev: "SIM-006" },
  { id: 7, name: "PLC panel L3-01", loc: "Factory Floor, Line 3", p: "plc", t: 27.3, rh: 55, dev: "SIM-007" },
  { id: 8, name: "Drive panel L3-02", loc: "Factory Floor, Line 3", p: "plc", t: 29.0, rh: 52, dev: "SIM-008" },
  { id: 9, name: "PLC panel L3-03", loc: "Factory Floor, Line 3", p: "plc", t: 26.1, rh: 57, dev: "SIM-009" },
  { id: 10, name: "Kiosk N1", loc: "North Gate", p: "out", t: 13.2, rh: 71, dev: "SIM-010" },
  { id: 11, name: "Kiosk N2", loc: "North Gate", p: "out", t: 14.0, rh: 66, dev: "SIM-011" },
  { id: 12, name: "Barrier controller", loc: "North Gate", p: "out", t: 12.6, rh: 63, dev: "SIM-012" },
];
const SCENARIOS = [
  ["NORMAL", "Normal room conditions"], ["HUMID_NIGHT", "Dehumidifier fails, humidity climbs"],
  ["CONDENSATION", "Cold surface drops below the dew point"], ["SILENT", "Sensor stops sending"],
  ["STUCK", "Sensor repeats its last values"], ["ERRATIC", "Random large jumps"],
  ["OUT_OF_RANGE", "Impossible values"], ["LOSSY", "40 % of readings lost"],
  ["BAD_SIGNATURE", "Wrong device key"], ["CLOCK_SKEW", "Device clock 10 minutes behind"],
];
const ISSUE_LABEL = { OFFLINE: "Offline", STUCK: "Stuck", OUT_OF_RANGE: "Impossible values", ERRATIC: "Erratic", LOSSY: "Losing readings", SIGNATURE_FAILURES: "Bad signatures", CLOCK_SKEW: "Clock off" };
const ROLE_NAMES = { TECHNICIAN: "Tariq Technician", OPERATOR: "Olivia Operator", MANAGER: "Morgan Manager", ADMIN: "Avery Admin" };

let simNow = Date.now();
const S = {
  eq: EQUIPMENT.map((e) => ({ ...e, prof: PROFILES[e.p], surface: e.t - PROFILES[e.p].offset, hist: [], scenario: "NORMAL", since: simNow })),
  alerts: [], health: {}, events: [], audit: [], services: [
    { name: "ingestion", state: "UP" }, { name: "analytics", state: "UP" }, { name: "sensor-health", state: "UP" },
    { name: "alerts", state: "UP" }, { name: "audit", state: "UP" }, { name: "service-watchdog", state: "UP" }],
  seq: 1, alertSeq: 101, auditSeq: 1240, failedLogins: 0, tampered: false,
  sel: { alert: null, sensor: null, eq: 2, alertsTab: "active", adminTab: "simulator" },
};

// seed history so charts are not empty: 3 simulated hours at 1-minute resolution
(function seed() {
  for (const e of S.eq) {
    let t = e.t, rh = e.rh;
    for (let i = 180; i > 0; i--) {
      const ts = simNow - i * 60000;
      t += (e.t - t) * 0.1 + (Math.random() - 0.5) * 0.12;
      rh += ((e.drift ? e.rh - 8 + (180 - i) * 0.045 : e.rh) - rh) * 0.1 + (Math.random() - 0.5) * 0.5;
      const surface = t - e.prof.offset + (e.drift ? -(180 - i) * 0.006 : 0);
      e.hist.push({ ts, t, rh, surface, dew: dewPoint(t, rh) });
    }
    const last = e.hist[e.hist.length - 1];
    Object.assign(e, { t: last.t, rh: last.rh, surface: last.surface });
    S.health[e.dev] = { status: "HEALTHY", issues: [], score: 100, lastSeen: simNow, missing: 0, flat: 0, trusted: true };
  }
  const base = simNow - 5 * 3600 * 1000;
  ["USER_LOGIN tech", "ALERT_ACKNOWLEDGED tech", "CORRECTIVE_ACTION_LOGGED tech", "SENSOR_ISSUE_CLOSED sensor-health", "ALERT_RESOLVED tech", "USER_LOGIN manager"]
    .forEach((x, i) => { const [action, actor] = x.split(" "); addAudit(actor, action, i % 2 ? "alert" : "user", i % 2 ? "98" : actor, base + i * 1500000); });
  S.alerts.push({ id: 98, sev: "WARNING", status: "RESOLVED", type: "CONDENSATION_RISK", eqId: 6, title: "Condensation risk: Kiosk N1", msg: "Dew-point margin 3.6 °C at 79 % RH.", opened: base, ack: base + 600000, ackBy: "tech", resolved: base + 3600000, resolvedBy: "tech", cleared: base + 3300000, actions: [{ type: "Inspected enclosure", notes: "Door seal worn on the left side. Taped temporarily.", by: "tech", at: base + 900000 }] });
})();

function addAudit(actor, action, entity, id, at) {
  const prev = S.audit.length ? S.audit[0].hash : "0".repeat(64);
  const hash = Array.from({ length: 64 }, (_, i) => "0123456789abcdef"[(prev.charCodeAt(i % 64) * 7 + action.length * 13 + i * 3 + S.auditSeq) % 16]).join("");
  S.audit.unshift({ id: S.auditSeq++, at: at || simNow, actor, action, entity, entityId: id, prev, hash });
  if (S.audit.length > 200) S.audit.pop();
}

// ------------------------------------------------------------------------------------------------
// Live simulation tick
// ------------------------------------------------------------------------------------------------
function riskOf(e) {
  const dew = dewPoint(e.t, clamp(e.rh, 1, 100));
  const margin = e.surface - dew;
  const p = e.prof;
  let level = "NORMAL";
  if (margin <= p.mCrit || e.rh >= p.rhCrit) level = "CRITICAL";
  else if (margin <= p.mWarn || e.rh >= p.rhWarn || e.t < p.tMin || e.t > p.tMax) level = "WARNING";
  const idx = Math.round(100 * Math.max(clamp((2 * p.mWarn - margin) / (2 * p.mWarn), 0, 1), clamp((e.rh - 40) / (p.rhCrit - 40), 0, 1)));
  return { dew, margin, level, idx };
}

function forecast(e) {
  const h = e.hist.slice(-30);
  if (h.length < 10) return null;
  const xs = h.map((p) => (p.ts - h[0].ts) / 60000), ys = h.map((p) => p.surface - p.dew);
  const n = xs.length, mx = xs.reduce((a, b) => a + b) / n, my = ys.reduce((a, b) => a + b) / n;
  let num = 0, den = 0; xs.forEach((x, i) => { num += (x - mx) * (ys[i] - my); den += (x - mx) ** 2; });
  const slope = den ? num / den : 0, now = ys[n - 1];
  const at = (m) => now + slope * m;
  const toCrit = slope < -0.001 ? (now - e.prof.mCrit) / -slope : null;
  return { m15: at(15), m30: at(30), m60: at(60), slope, toCrit };
}

function tick() {
  simNow += 1000 * SPEED;
  for (const e of S.eq) {
    const mins = (simNow - e.since) / 60000;
    const h = S.health[e.dev];
    let targetRh = e.drift ? Math.min(74, 58 + mins * 0.05) : EQUIPMENT.find((x) => x.id === e.id).rh;
    let targetSurf = e.t - e.prof.offset - (e.drift ? Math.min(1.6, mins * 0.006) : 0);
    if (e.scenario === "HUMID_NIGHT") targetRh = Math.min(88, e.rh + 2.2);
    if (e.scenario === "CONDENSATION") targetSurf = dewPoint(e.t, e.rh) - 0.8;
    if (e.scenario !== "SILENT" && e.scenario !== "STUCK") {
      e.t += (EQUIPMENT.find((x) => x.id === e.id).t - e.t) * 0.08 + (Math.random() - 0.5) * 0.1;
      e.rh += (targetRh - e.rh) * 0.12 + (Math.random() - 0.5) * 0.4;
      e.surface += (targetSurf - e.surface) * 0.15 + (Math.random() - 0.5) * 0.05;
      e.rh = clamp(e.rh, 2, 99.5);
    }
    // health rules (simplified copy of HealthEvaluator)
    const issues = new Set(h.issues);
    if (e.scenario === "SILENT") { if ((simNow - h.lastSeen) / 1000 > 90) issues.add("OFFLINE"); }
    else { h.lastSeen = simNow; issues.delete("OFFLINE"); }
    if (e.scenario === "STUCK") { h.flat += SPEED / 15; if (h.flat >= 20) issues.add("STUCK"); } else { h.flat = 0; issues.delete("STUCK"); }
    toggle(issues, "OUT_OF_RANGE", e.scenario === "OUT_OF_RANGE");
    toggle(issues, "ERRATIC", e.scenario === "ERRATIC");
    toggle(issues, "LOSSY", e.scenario === "LOSSY");
    toggle(issues, "SIGNATURE_FAILURES", e.scenario === "BAD_SIGNATURE");
    toggle(issues, "CLOCK_SKEW", e.scenario === "CLOCK_SKEW");
    h.missing = e.scenario === "LOSSY" ? 38 + Math.random() * 6 : e.scenario === "SILENT" ? 100 : Math.random() < 0.1 ? 1 : 0;
    updateHealth(e, h, [...issues]);
    if (e.scenario !== "SILENT" && h.trusted !== false) {
      const r = riskOf(e);
      e.hist.push({ ts: simNow, t: e.t, rh: e.rh, surface: e.surface, dew: r.dew });
      if (e.hist.length > 240) e.hist.shift();
      updateRiskAlert(e, r);
    }
  }
  listeners.forEach((fn) => fn());
}
function toggle(set, k, on) { if (on) set.add(k); else set.delete(k); }

function updateHealth(e, h, issues) {
  const before = new Set(h.issues);
  issues.filter((i) => !before.has(i)).forEach((i) => {
    S.events.unshift({ dev: e.dev, issue: i, start: simNow, end: null, sev: ["OFFLINE", "STUCK"].includes(i) ? "CRITICAL" : "WARNING" });
    raise("HEALTH:" + i + ":" + e.dev, ["OFFLINE", "STUCK"].includes(i) ? "CRITICAL" : "WARNING", "SENSOR_" + i, e.id, (ISSUE_LABEL[i] || i) + ": " + e.dev, issueMessage(i, e));
    addAudit("sensor-health", "SENSOR_ISSUE_OPENED", "sensor", e.dev);
  });
  [...before].filter((i) => !issues.includes(i)).forEach((i) => {
    const ev = S.events.find((x) => x.dev === e.dev && x.issue === i && !x.end); if (ev) ev.end = simNow;
    clearSignal("HEALTH:" + i + ":" + e.dev);
    addAudit("sensor-health", "SENSOR_ISSUE_CLOSED", "sensor", e.dev);
  });
  h.issues = issues;
  const pen = { OFFLINE: 100, STUCK: 70, OUT_OF_RANGE: 50, ERRATIC: 40, LOSSY: 25, SIGNATURE_FAILURES: 20, CLOCK_SKEW: 15 };
  h.score = clamp(100 - issues.reduce((a, i) => a + pen[i], 0), 0, 100);
  h.status = issues.includes("OFFLINE") ? "OFFLINE" : issues.includes("STUCK") ? "STUCK" : (issues.includes("OUT_OF_RANGE") || issues.includes("ERRATIC")) ? "FAULTY" : issues.length ? "DEGRADED" : "HEALTHY";
  h.trusted = !issues.some((i) => ["OFFLINE", "STUCK", "OUT_OF_RANGE", "ERRATIC"].includes(i));
}
function issueMessage(i, e) {
  return { OFFLINE: "No readings for 1 min 30 s.", STUCK: "The last 20 readings did not change at all.", OUT_OF_RANGE: "Humidity above 100 % reported 3 times.", ERRATIC: "4 sudden jumps in the last 15 minutes.", LOSSY: "38 % of expected readings did not arrive.", SIGNATURE_FAILURES: "5 requests failed signature checks.", CLOCK_SKEW: "Device clock differs from server time by 600 seconds." }[i];
}

function updateRiskAlert(e, r) {
  if (e.level !== r.level) {
    const key = "ANALYTICS:CONDENSATION_RISK:" + e.id;
    if (r.level === "NORMAL") clearSignal(key);
    else raise(key, r.level, "CONDENSATION_RISK", e.id, "Condensation risk: " + e.name, `Dew-point margin ${fmt(r.margin)} °C at ${fmt(e.rh)} % RH (dew point ${fmt(r.dew)} °C).`);
    e.level = r.level;
  }
}
function raise(key, sev, type, eqId, title, msg) {
  const a = S.alerts.find((x) => x.key === key && x.status !== "RESOLVED");
  if (a) { if (sev === "CRITICAL" && a.sev !== "CRITICAL") { a.sev = "CRITICAL"; addAudit("alerts", "ALERT_SEVERITY_RAISED", "alert", a.id); } a.msg = msg; a.cleared = null; return; }
  const id = S.alertSeq++;
  S.alerts.unshift({ id, key, sev, type, eqId, title, msg, status: "OPEN", opened: simNow, actions: [] });
  addAudit("alerts", "ALERT_OPENED", "alert", id);
  alertListeners.forEach((fn) => fn(title));
}
function clearSignal(key) {
  const a = S.alerts.find((x) => x.key === key && x.status !== "RESOLVED");
  if (a && !a.cleared) { a.cleared = simNow; addAudit("alerts", "ALERT_CONDITION_CLEARED", "alert", a.id); }
}


const DAY = 86400000;
(function history() {
  let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const kinds = [["CONDENSATION_RISK", "Condensation risk", "Dew-point margin dropped below the warning threshold."], ["SENSOR_OFFLINE", "Sensor offline", "No readings received for 1 min 30 s."], ["SENSOR_STUCK", "Sensor stuck", "The last 20 readings did not change at all."], ["HIGH_HUMIDITY", "High humidity", "Relative humidity above the profile limit."]];
  const acts = ["Inspected equipment", "Turned on dehumidifier", "Closed door or vent", "Replaced sensor"];
  const made = [];
  for (let i = 0; i < 46; i++) {
    const e = S.eq[Math.floor(rnd() * S.eq.length)], k = kinds[Math.floor(rnd() * kinds.length)];
    const opened = simNow - Math.floor((1 + rnd() * 20) * DAY), ack = opened + Math.floor((2 + rnd() * 20) * 60000), res = ack + Math.floor((15 + rnd() * 150) * 60000);
    const by = rnd() < .7 ? "tech" : "admin";
    made.push({ id: i + 1, key: `HIST:${i}`, sev: rnd() < .35 ? "CRITICAL" : "WARNING", type: k[0], eqId: e.id, title: `${k[1]}: ${k[0].startsWith("SENSOR") ? e.dev : e.name}`, msg: k[2], status: "RESOLVED", opened, ack, ackBy: by, resolved: res, resolvedBy: by, cleared: res - 60000,
      actions: rnd() < .7 ? [{ type: acts[Math.floor(rnd() * acts.length)], notes: "", by, at: ack + 120000 }] : [] });
  }
  S.alerts.push(...made.sort((a, b) => b.opened - a.opened));
  const events = [];
  made.forEach((a) => { events.push([a.opened, "alerts", "ALERT_OPENED", "alert", a.id]); events.push([a.ack, a.ackBy, "ALERT_ACKNOWLEDGED", "alert", a.id]); events.push([a.resolved, a.resolvedBy, "ALERT_RESOLVED", "alert", a.id]); });
  for (let i = 0; i < 40; i++) { const t = simNow - Math.floor(rnd() * 20 * DAY); const who = ["tech", "manager", "operator", "admin"][Math.floor(rnd() * 4)]; events.push([t, who, rnd() < .85 ? "USER_LOGIN" : "USER_LOGIN_FAILED", "user", who]); }
  const prior = S.audit.slice(); S.audit.length = 0;
  events.sort((a, b) => a[0] - b[0]).forEach(([t, actor, action, ent, id]) => addAudit(actor, action, ent, String(id), t));
  prior.reverse().forEach((a) => addAudit(a.actor, a.action, a.entity, a.entityId, a.at));
})();
S.eq.find((e) => e.dev === "SIM-010").scenario = "STUCK";
S.health["SIM-010"].flat = 19;
S.tampered = false;
let timer = null;
export const sim = {
  S, PROFILES, SCENARIOS, ISSUE_LABEL, ROLE_NAMES, riskOf, forecast, addAudit, issueMessage, dewPoint,
  now: () => simNow,
  start() { if (!timer) timer = setInterval(tick, 1000); },
  onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
  onAlert(fn) { alertListeners.add(fn); return () => alertListeners.delete(fn); },
};
