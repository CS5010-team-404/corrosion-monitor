import { api, post } from "../lib/api";
import type * as T from "./types";

// Every call the UI makes, in one file. Team backend paths are on the left of the split in docs/CONTRACTS.md;
// data-platform paths (/api/health, /api/sensors, /api/admin/simulator, /api/admin/sensors) go to VITE_PLATFORM_URL.
const q = (params: Record<string, string | number | undefined | null>) => {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => v != null && v !== "" && s.set(k, String(v)));
  const str = s.toString();
  return str ? `?${str}` : "";
};

// auth (team backend)
export const login = (username: string, password: string) => post<T.LoginResult>("/api/auth/login", { username, password });

// analytics (Aayushee)
export const equipmentStatus = () => api<T.EquipmentStatus[]>("/api/equipment/status");
export const assessments = (equipmentId: number, minutes: number) => api<T.AssessmentPoint[]>(`/api/equipment/${equipmentId}/assessments${q({ minutes })}`);

// data platform (Joshua)
export const forecast = (sensorId: number) => api<T.ForecastView>(`/api/sensors/${sensorId}/forecast`);
export const sensorsHealth = () => api<T.SensorHealth[]>("/api/health/sensors");
export const sensorDetail = (sensorId: number) => api<T.SensorDetail>(`/api/health/sensors/${sensorId}?minutes=60`);
export const healthEvents = (limit = 50) => api<T.HealthEvent[]>(`/api/health/events${q({ limit })}`);
export const services = () => api<T.ServiceStatus[]>("/api/health/services");
export const devices = () => api<T.Device[]>("/api/sensors");
export const registerDevice = (body: { deviceId: string; equipmentId: number; equipmentName: string; locationName: string; kind: string }) =>
  post<{ deviceId: string; secret: string }>("/api/admin/sensors", body);
export const simulator = () => api<T.SimulatorDevice[]>("/api/admin/simulator");
export const scenarios = () => api<T.ScenarioOption[]>("/api/admin/simulator/scenarios");
export const setScenario = (deviceId: string, scenario: string) => post(`/api/admin/simulator/${encodeURIComponent(deviceId)}/scenario`, { scenario });

// alerts (Dumebi)
export const alerts = (status = "ALL", limit = 500) => api<T.Alert[]>(`/api/alerts${q({ status, limit })}`);
export const alertDetail = (id: number) => api<T.AlertDetail>(`/api/alerts/${id}`);
export const acknowledge = (id: number) => post<T.Alert>(`/api/alerts/${id}/acknowledge`);
export const resolve = (id: number, notes?: string) => post<T.Alert>(`/api/alerts/${id}/resolve`, { notes: notes ?? "" });
export const addAction = (id: number, actionType: string, notes: string) => post<T.CorrectiveAction>(`/api/alerts/${id}/actions`, { actionType, notes });

// audit (Anela)
export const auditLog = (params: { size?: number; page?: number; actor?: string; action?: string } = {}) => api<T.AuditEntry[]>(`/api/audit${q({ size: 500, ...params })}`);
export const verifyChain = () => post<T.ChainVerification>("/api/audit/verify");

// reports (Aayushee with Dumebi)
export const reportSummary = (from: number | null, to: number | null, location: string) =>
  api<T.ReportSummary>(`/api/reports/summary${q({ from: from ? new Date(from).toISOString() : null, to: to ? new Date(to).toISOString() : null, location: location === "all" ? null : location })}`);
export const reportFile = (kind: "csv" | "pdf", from: number | null, to: number | null, location: string) =>
  api<Blob>(`/api/reports/summary.${kind}${q({ from: from ? new Date(from).toISOString() : null, to: to ? new Date(to).toISOString() : null, location: location === "all" ? null : location })}`);

// configuration (platform/admin)
export const profiles = () => api<T.Profile[]>("/api/profiles");
export const users = () => api<T.User[]>("/api/admin/users");
