import { api, post } from "../lib/api";
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

// analytics (Aayushee)
export const equipmentStatus = () => api<T.EquipmentStatus[]>("/api/v1/equipment/status");
export const assessments = (equipmentId: number, minutes: number) => api<T.AssessmentPoint[]>(`/api/v1/equipment/${equipmentId}/assessments${q({ minutes })}`);

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
export const alerts = (status = "ALL", limit = 500) => api<T.Alert[]>(`/api/v1/alerts${q({ status, limit })}`);
export const alertDetail = (id: number) => api<T.AlertDetail>(`/api/v1/alerts/${id}`);
export const acknowledge = (id: number) => post<T.Alert>(`/api/v1/alerts/${id}/acknowledge`);
export const resolve = (id: number, notes?: string) => post<T.Alert>(`/api/v1/alerts/${id}/resolve`, { notes: notes ?? "" });
export const addAction = (id: number, actionType: string, notes: string) => post<T.CorrectiveAction>(`/api/v1/alerts/${id}/actions`, { actionType, notes });

// audit (Anela)
export const auditLog = (params: { size?: number; page?: number; actor?: string; action?: string } = {}) => api<T.AuditEntry[]>(`/api/v1/audit${q({ size: 500, ...params })}`);
export const verifyChain = () => post<T.ChainVerification>("/api/v1/audit/verify");

// reports (Aayushee with Dumebi)
export const reportSummary = (from: number | null, to: number | null, location: string) =>
  api<T.ReportSummary>(`/api/v1/reports/summary${q({ from: from ? new Date(from).toISOString() : null, to: to ? new Date(to).toISOString() : null, location: location === "all" ? null : location })}`);
export const reportFile = (kind: "csv" | "pdf", from: number | null, to: number | null, location: string) =>
  api<Blob>(`/api/v1/reports/summary.${kind}${q({ from: from ? new Date(from).toISOString() : null, to: to ? new Date(to).toISOString() : null, location: location === "all" ? null : location })}`);

// configuration (platform/admin)
export const profiles = () => api<T.Profile[]>("/api/v1/profiles");
export const users = () => api<T.User[]>("/api/v1/admin/users");
