import { api, post, type Pending } from "../lib/api";
import type * as T from "./types";

// Every call the UI makes, in one file. Team backend paths are on the left of the split in docs/CONTRACTS.md;
// data-platform paths (/api/v1/health, /api/v1/sensors, /api/v1/admin/simulator, /api/v1/admin/sensors) go to VITE_PLATFORM_URL.
const q = (params: Record<string, string | number | undefined | null>) => {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => v != null && v !== "" && s.set(k, String(v)));
  const str = s.toString();
  return str ? `?${str}` : "";
};

// auth (team backend): sign in with email; the session shows the email as the username
interface LoginResponse { token: string; expiresAt: string; email: string; fullName: string; role: T.Role; }
export const login = async (email: string, password: string): Promise<T.LoginResult> => {
  const r = await post<LoginResponse>("/api/v1/auth/login", { email, password });
  return { token: r.token, expiresAt: r.expiresAt, username: r.email, fullName: r.fullName, role: r.role };
};

// Endpoints teammates haven't built yet, with owners (docs/GAPS.md). On 404/501 the page shows "isn't available yet"
// instead of an error and stops polling; with VITE_MOCK_FALLBACK=true the mock backend answers them as sample data.
// Platform and auth endpoints are not listed, so they are never replaced. Remove an entry once the endpoint ships.
const pending = (key: string, what: string, endpoint: string, owner: string): Pending => ({ key, what, endpoint, owner });
export const PENDING = {
  equipmentStatus: pending("equipmentStatus", "Equipment status", "GET /api/v1/equipment/status", "Aayushee"),
  assessments: pending("assessments", "Equipment history", "GET /api/v1/equipment/{id}/assessments", "Aayushee"),
  alerts: pending("alerts", "Alerts", "GET /api/v1/alerts", "Dumebi"),
  alertDetail: pending("alertDetail", "Alert details", "GET /api/v1/alerts/{id}", "Dumebi"),
  acknowledge: pending("acknowledge", "Acknowledging alerts", "POST /api/v1/alerts/{id}/acknowledge", "Dumebi"),
  resolve: pending("resolve", "Resolving alerts", "POST /api/v1/alerts/{id}/resolve", "Dumebi"),
  addAction: pending("addAction", "Logging corrective actions", "POST /api/v1/alerts/{id}/actions", "Dumebi"),
  auditLog: pending("auditLog", "The audit log", "GET /api/v1/audit", "Anela"),
  verifyChain: pending("verifyChain", "Chain verification", "POST /api/v1/audit/verify", "Anela"),
  reportSummary: pending("reportSummary", "The report summary", "GET /api/v1/reports/summary", "Aayushee with Dumebi"),
  reportCsv: pending("reportCsv", "The CSV report", "GET /api/v1/reports/summary.csv", "Aayushee with Dumebi"),
  reportPdf: pending("reportPdf", "The PDF report", "GET /api/v1/reports/summary.pdf", "Aayushee with Dumebi"),
  profiles: pending("profiles", "Equipment profiles", "GET /api/v1/profiles", "team"),
  users: pending("users", "The user list", "GET /api/v1/admin/users", "team"),
} as const;

const range = (from: number | null, to: number | null, location: string) =>
  q({ from: from ? new Date(from).toISOString() : null, to: to ? new Date(to).toISOString() : null, location: location === "all" ? null : location });

// analytics (Aayushee)
export const equipmentStatus = () => api<T.EquipmentStatus[]>("/api/v1/equipment/status", {}, PENDING.equipmentStatus);
export const assessments = (equipmentId: number, minutes: number) => api<T.AssessmentPoint[]>(`/api/v1/equipment/${equipmentId}/assessments${q({ minutes })}`, {}, PENDING.assessments);

// data platform (Joshua)
export const forecast = (sensorId: number) => api<T.ForecastView>(`/api/v1/sensors/${sensorId}/forecast`);
export const sensorsHealth = () => api<T.SensorHealth[]>("/api/v1/health/sensors");
export const sensorDetail = (sensorId: number) => api<T.SensorDetail>(`/api/v1/health/sensors/${sensorId}?minutes=60`);
export const healthEvents = (limit = 50) => api<T.HealthEvent[]>(`/api/v1/health/events${q({ limit })}`);
export const services = () => api<T.ServiceStatus[]>("/api/v1/health/services");
export const devices = () => api<T.Device[]>("/api/v1/sensors");
export const registerDevice = (body: { deviceId: string; equipmentId: number; equipmentName: string; locationName: string; kind: string }) =>
  post<{ deviceId: string; secret: string }>("/api/v1/admin/sensors", body);
export const simulator = () => api<T.SimulatorDevice[]>("/api/v1/admin/simulator");
export const scenarios = () => api<T.ScenarioOption[]>("/api/v1/admin/simulator/scenarios");
export const setScenario = (deviceId: string, scenario: string) => post(`/api/v1/admin/simulator/${encodeURIComponent(deviceId)}/scenario`, { scenario });

// alerts (Dumebi)
export const alerts = (status = "ALL", limit = 500) => api<T.Alert[]>(`/api/v1/alerts${q({ status, limit })}`, {}, PENDING.alerts);
export const alertDetail = (id: number) => api<T.AlertDetail>(`/api/v1/alerts/${id}`, {}, PENDING.alertDetail);
export const acknowledge = (id: number) => post<T.Alert>(`/api/v1/alerts/${id}/acknowledge`, undefined, PENDING.acknowledge);
export const resolve = (id: number, notes?: string) => post<T.Alert>(`/api/v1/alerts/${id}/resolve`, { notes: notes ?? "" }, PENDING.resolve);
export const addAction = (id: number, actionType: string, notes: string) => post<T.CorrectiveAction>(`/api/v1/alerts/${id}/actions`, { actionType, notes }, PENDING.addAction);

// audit (Anela)
export const auditLog = (params: { size?: number; page?: number; actor?: string; action?: string } = {}) => api<T.AuditEntry[]>(`/api/v1/audit${q({ size: 500, ...params })}`, {}, PENDING.auditLog);
export const verifyChain = () => post<T.ChainVerification>("/api/v1/audit/verify", undefined, PENDING.verifyChain);

// reports (Aayushee with Dumebi)
export const reportSummary = (from: number | null, to: number | null, location: string) =>
  api<T.ReportSummary>(`/api/v1/reports/summary${range(from, to, location)}`, {}, PENDING.reportSummary);
export const reportFile = (kind: "csv" | "pdf", from: number | null, to: number | null, location: string) =>
  api<Blob>(`/api/v1/reports/summary.${kind}${range(from, to, location)}`, {}, kind === "csv" ? PENDING.reportCsv : PENDING.reportPdf);

// configuration (team)
export const profiles = () => api<T.Profile[]>("/api/v1/profiles", {}, PENDING.profiles);
export const users = () => api<T.User[]>("/api/v1/admin/users", {}, PENDING.users);
