import { config, isPlatformPath } from "./config";

const TOKEN_KEY = "cw.token";
export const tokenStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (t: string) => sessionStorage.setItem(TOKEN_KEY, t),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** An endpoint a teammate hasn't built yet (src/api/endpoints.ts keeps the list with owners). */
export interface Pending {
  key: string;
  /** What the page is missing, as a sentence subject: "Equipment status". */
  what: string;
  /** "GET /api/v1/equipment/status" */
  endpoint: string;
  owner: string;
}

/** A pending endpoint answered 404 or 501: shown as a calm "not built yet" panel, not as an error. */
export class NotBuiltError extends ApiError {
  constructor(status: number, public pending: Pending) {
    super(status, `${pending.what} isn't available yet. It needs ${pending.endpoint} (owner: ${pending.owner}).`);
  }
}

/** Status codes that mean "this endpoint doesn't exist yet" rather than "something broke". */
export const isNotBuiltStatus = (status: number) => status === 404 || status === 501;
export const isNotBuilt = (err: unknown): err is ApiError => err instanceof ApiError && isNotBuiltStatus(err.status);

export const NETWORK_ERROR_MESSAGE = "Can't reach the server. Check your connection and try again.";

/**
 * A readable message for anything a request can throw. fetch rejects with a TypeError when the server can't be
 * reached (offline, DNS, CORS, server down), whose browser text ("Failed to fetch", "Load failed") means nothing to users.
 */
export function errorMessage(err: unknown, fallback = "Something went wrong. Please try again."): string {
  if (err instanceof ApiError) return err.message || fallback;
  if (err instanceof TypeError) return NETWORK_ERROR_MESSAGE;
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}

type Handler = (path: string, init: RequestInit) => Promise<unknown>;
let mockHandler: Handler | null = null;
/** The mock backend registers itself here when VITE_USE_MOCKS=true. */
export function setMockHandler(h: Handler) {
  mockHandler = h;
}

// ---- endpoints that aren't built yet ---------------------------------------------------------------------------
// Once a pending endpoint answers 404/501 it is not requested again until the page is reloaded: no repeated 404s in
// the console. With VITE_MOCK_FALLBACK=true the mock backend answers it instead, and the data is marked as sample.

const notBuilt = new Map<string, Pending>();
const sampleValues = new WeakSet<object>();
const sampleKeys = new Map<string, Pending>();
const sampleListeners = new Set<() => void>();
let sampleSnapshot: Pending[] = [];

function markSample(p: Pending, value: unknown) {
  if (value && typeof value === "object") sampleValues.add(value);
  if (!sampleKeys.has(p.key)) {
    sampleKeys.set(p.key, p);
    sampleSnapshot = [...sampleKeys.values()];
    sampleListeners.forEach((l) => l());
  }
}

/** True when a value came from the mock fallback rather than a real endpoint. */
export const isSample = (value: unknown) => !!value && typeof value === "object" && sampleValues.has(value);

/** The pending endpoints currently answered with sample data (for the sidebar note); use with useSyncExternalStore. */
export const sampleSources = {
  subscribe(l: () => void) {
    sampleListeners.add(l);
    return () => void sampleListeners.delete(l);
  },
  get: () => sampleSnapshot,
};

let fallbackHandler: Promise<Handler> | null = null;
async function fallback(p: Pending, path: string, init: RequestInit) {
  // Loaded only when the flag is on, so the simulation stays out of the main bundle.
  fallbackHandler ??= import("../mock/server").then((m) => m.mockFallback);
  const value = await (await fallbackHandler)(path, init);
  markSample(p, value);
  return value;
}

// ---- requests ---------------------------------------------------------------------------------------------------

/** One place for base URLs, JWT, JSON and readable errors. Every API module goes through this. */
export async function api<T>(path: string, init: RequestInit = {}, pending?: Pending): Promise<T> {
  if (mockHandler) return (await mockHandler(path, init)) as T;
  if (pending && notBuilt.has(pending.key)) {
    if (config.mockFallback) return (await fallback(pending, path, init)) as T;
    throw new NotBuiltError(404, pending);
  }
  const headers = new Headers(init.headers);
  const token = tokenStore.get();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const base = isPlatformPath(path) ? config.platformBase : config.apiBase;
  const res = await fetch(base + path, { ...init, headers });
  if (res.status === 401 && token) {
    tokenStore.clear();
    window.dispatchEvent(new Event("cw:unauthorized"));
  }
  if (pending && isNotBuiltStatus(res.status)) {
    notBuilt.set(pending.key, pending);
    if (config.mockFallback) return (await fallback(pending, path, init)) as T;
    throw new NotBuiltError(res.status, pending);
  }
  if (!res.ok) {
    let message = res.statusText || `Request failed (${res.status})`;
    try {
      const body = await res.json();
      message = body.message ?? body.detail ?? body.error ?? message;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  const type = res.headers.get("Content-Type") || "";
  return (type.includes("json") ? await res.json() : await res.blob()) as T;
}

export const post = <T>(path: string, body?: unknown, pending?: Pending) =>
  api<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }, pending);

/** Saves a Blob as a file download. */
export function saveBlob(blob: Blob, name: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
