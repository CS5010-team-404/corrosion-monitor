import { api, patch, post, put, type Pending } from "../lib/api";
import type * as T from "./types";
import { DAY } from "../lib/format";

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
  assessments: pending("assessments", "Equipment history", "GET /api/v1/equipment/{id}/assessments", "Aayushee"),
} as const;

// The picker's `to` is the start of the last chosen day; the backend's period is [from, to), so send the end of that day.
const range = (from: number | null, to: number | null, location: string) =>
  q({ from: from ? new Date(from).toISOString() : null, to: to ? new Date(to + DAY).toISOString() : null, location: location === "all" ? null : location });

// analytics (Aayushee)
export const equipmentStatus = () => api<T.EquipmentStatus[]>("/api/v1/equipment/status");
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
export const setSensorInterval = (sensorId: number, expectedIntervalS: number) =>
  patch<T.Device>(`/api/v1/admin/sensors/${sensorId}`, { expectedIntervalS });
export const simulator = () => api<T.SimulatorDevice[]>("/api/v1/admin/simulator");
export const scenarios = () => api<T.ScenarioOption[]>("/api/v1/admin/simulator/scenarios");
export const setScenario = (deviceId: string, scenario: string) => post(`/api/v1/admin/simulator/${encodeURIComponent(deviceId)}/scenario`, { scenario });

// alerts (Dumebi)
export const alerts = (status = "ALL", limit = 500) => api<T.Alert[]>(`/api/v1/alerts${q({ status, limit })}`, {});
export const alertDetail = (id: number) => api<T.AlertDetail>(`/api/v1/alerts/${id}`, {});
export const acknowledge = (id: number) => post<T.Alert>(`/api/v1/alerts/${id}/acknowledge`);
export const resolve = (id: number, notes?: string) => post<T.Alert>(`/api/v1/alerts/${id}/resolve`, { notes: notes ?? "" });
export const addAction = (id: number, actionType: string, notes: string) => post<T.CorrectiveAction>(`/api/v1/alerts/${id}/actions`, { actionType, notes });

// audit (Anela)
export interface AuditQuery { page: number; size: number; actor?: string | null; from?: string | null; to?: string | null }
/**
 * GET /api/v1/audit (page from 0). Accepts both shapes: the agreed { items, page, size, total } (paged: true) and the
 * older plain array of one page (paged: false, total unknown, so the caller asks for the maximum and pages locally).
 */
export const auditPage = async (params: AuditQuery): Promise<T.AuditPage & { paged: boolean }> => {
  const r = await api<T.AuditEntry[] | T.AuditPage>(`/api/v1/audit${q({ ...params })}`, {});
  return Array.isArray(r) ? { items: r, page: params.page, size: r.length, total: r.length, paged: false } : { ...r, paged: true };
};
/** The backend's page-size cap for GET /api/v1/audit. */
export const AUDIT_MAX_PAGE = 200;
export const verifyChain = () => post<T.ChainVerification>("/api/v1/audit/verify");

// reports (Dumebi; risk figures from Aayushee)
export const reportSummary = (from: number | null, to: number | null, location: string) =>
  api<T.ReportSummary>(`/api/v1/reports/summary${range(from, to, location)}`, {});
export const reportFile = (kind: "csv" | "pdf", from: number | null, to: number | null, location: string) =>
  api<Blob>(`/api/v1/reports/summary.${kind}${range(from, to, location)}`, {});

// configuration (profiles: Anela)
export const profiles = () => api<T.Profile[]>("/api/v1/profiles");
/** Admins only. The backend validates (ProfileValidator) and answers 400 with the problems as readable sentences. */
export const updateProfile = (id: number, p: Omit<T.Profile, "id">) => put<T.Profile>(`/api/v1/profiles/${id}`, p);

// users (admin only; team backend)
export const users = () => api<T.User[]>("/api/v1/admin/users");
export const unlockUser = (id: number) => post<T.User>(`/api/v1/admin/users/${id}/unlock`);
export const setUserRole = (id: number, role: T.Role) => patch<T.User>(`/api/v1/admin/users/${id}`, { role });
