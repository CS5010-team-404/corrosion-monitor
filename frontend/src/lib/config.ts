// Render passes service URLs as bare hosts ("corrosion-watch-api.onrender.com") or full URLs. Normalise both.
function url(value: string | undefined): string {
  if (!value) return "";
  const v = value.replace(/\/$/, "");
  return /^https?:\/\//.test(v) ? v : `https://${v}`;
}
const toWs = (http: string) => http.replace(/^http/, "ws");
const sameOrigin = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}`;

const apiBase = url(import.meta.env.VITE_API_URL);
const platformBase = url(import.meta.env.VITE_PLATFORM_URL) || apiBase;

export const config = {
  /** Team backend: auth, analytics, alerts, audit, reports, admin. "" = same origin (dev proxy). */
  apiBase,
  /** Data platform: ingestion, sensor health, simulator, forecast. Same as apiBase when embedded. */
  platformBase,
  wsApi: apiBase ? `${toWs(apiBase)}/ws` : `${sameOrigin}/ws`,
  wsPlatform: platformBase ? `${toWs(platformBase)}/ws` : `${sameOrigin}/ws-platform`,
  useMocks: import.meta.env.VITE_USE_MOCKS === "true",
  /** Connect to the team backend's WebSocket (/topic/risk, /topic/alerts). Off until the backend has /ws. */
  backendLive: import.meta.env.VITE_BACKEND_LIVE === "true",
  /** Demo only: endpoints that aren't built yet (404/501) are answered by the mock backend, marked as sample data. */
  mockFallback: import.meta.env.VITE_MOCK_FALLBACK === "true",
};

/** Paths served by the data platform rather than the team backend. */
export const PLATFORM_PATHS = ["/api/v1/ingest", "/api/v1/health", "/api/v1/admin/simulator", "/api/v1/admin/sensors", "/api/v1/sensors", "/api/v1/forecast"];
export const isPlatformPath = (path: string) => PLATFORM_PATHS.some((p) => path.startsWith(p));
