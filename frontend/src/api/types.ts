// Shapes returned by the API. These are the contract in docs/CONTRACTS.md; if the team backend returns
// something different, adapt it in endpoints.ts (one place) and keep these types stable for the pages.
export type Role = "TECHNICIAN" | "OPERATOR" | "MANAGER" | "ADMIN";
export interface Session { username: string; fullName: string; role: Role; }
export interface LoginResult extends Session { token: string; expiresAt: string; }

export type RiskLevel = "NORMAL" | "WARNING" | "CRITICAL" | "UNKNOWN";
export type ProfileKind = "it" | "tel" | "plc" | "out";

export interface EquipmentStatus {
  equipmentId: number; equipmentName: string; locationName: string; profileName: string; profileKind: ProfileKind;
  riskLevel: RiskLevel; riskIndex: number | null;
  temperatureC: number | null; humidityPct: number | null; surfaceTempC: number | null; dewPointC: number | null; marginC: number | null;
  forecastMargin15C: number | null; forecastMargin30C: number | null; forecastMargin60C: number | null;
  predictedCondensationAt: string | null; updatedAt: string | null;
  marginWarningC: number; marginCriticalC: number; rhWarningPct: number; rhCriticalPct: number;
  tempMinC: number; tempMaxC: number; surfaceOffsetC: number;
  sensorId: number | null; deviceId: string | null; trusted: boolean; timeOfWetnessMin24h?: number | null;
}

export interface AssessmentPoint { measuredAt: string; temperatureC: number; humidityPct: number; surfaceTempC: number; dewPointC: number; marginC: number; }

export interface ForecastView {
  margin15C: number; margin30C: number; margin60C: number; predictedCondensationAt: string | null;
  risk30: number; risk60: number; warn30: boolean; warn60: boolean; method: "model" | "trend"; modelVersion: string;
}

export type HealthStatus = "HEALTHY" | "DEGRADED" | "FAULTY" | "STUCK" | "OFFLINE" | "UNKNOWN";
export interface SensorHealth {
  sensorId: number; deviceId: string; kind: string; model: string; expectedIntervalSeconds: number;
  equipmentId: number | null; equipmentName: string; locationName: string;
  status: HealthStatus; connectivity: string; score: number; trusted: boolean; issues: string[];
  lastSeenAt: string | null; missingPct: number | null; signatureFailures: number | null; statusSince: string | null;
}
export interface HealthEvent { id: number; sensorId: number; deviceId: string; issue: string; severity: string; startedAt: string; endedAt: string | null; durationSeconds: number; details: Record<string, unknown>; }
export interface ReadingPoint { measuredAt: string; temperatureC: number; humidityPct: number; }
export interface SensorDetail { sensor: SensorHealth; events: HealthEvent[]; readings: ReadingPoint[]; }
export interface ServiceStatus { service: string; state: "UP" | "FAILING" | "DOWN"; lastRunAt: string; lastError: string | null; errorCount: number; }

export type AlertStatus = "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
export interface Alert {
  id: number; type: string; source: string; severity: "WARNING" | "CRITICAL"; status: AlertStatus; escalationLevel: number;
  equipmentId: number | null; equipmentName: string | null; locationName?: string | null; sensorId: number | null; deviceId: string | null;
  title: string; message: string | null; openedAt: string; acknowledgedAt: string | null; acknowledgedBy: string | null;
  resolvedAt: string | null; resolvedBy: string | null; conditionClearedAt: string | null;
}
export interface CorrectiveAction { id: number; actionType: string; notes: string | null; username: string; performedAt: string; }
export interface AlertDetail { alert: Alert; actions: CorrectiveAction[]; }

export interface AuditEntry { id: number; occurredAt: string; actorType: string; actor: string; action: string; entityType: string | null; entityId: string | null; prevHash: string; hash: string; }
export interface ChainVerification { intact: boolean; checked: number; firstBrokenId: number | null; }

export interface ReportSummary {
  from: string | null; to: string | null; alertsOpened: number; meanMinutesToAcknowledge: number | null; correctiveActions: number;
  equipment: { equipmentId: number; equipmentName: string; locationName: string; normalPct: number; warningPct: number; criticalPct: number }[];
}

export interface Profile { id: number; name: string; rhWarningPct: number; rhCriticalPct: number; marginWarningC: number; marginCriticalC: number; tempMinC: number; tempMaxC: number; surfaceOffsetC: number; }
export interface User { id: number; username: string; fullName: string; email: string | null; role: Role; enabled: boolean; lockedUntil: string | null; lastLoginAt: string | null; }
export interface Device { id: number; deviceId: string; kind: string; model: string; expectedIntervalS: number; active: boolean; equipmentId: number | null; equipmentName: string | null; locationName: string | null; }
export interface SimulatorDevice { deviceId: string; scenario: string; intervalSeconds: number; lastHttpStatus: number | null; sent: number; failed: number; }
export interface ScenarioOption { name: string; description: string; }
