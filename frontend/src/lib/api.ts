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

type Handler =(path: string, init: RequestInit) => Promise<unknown>;
let mockHandler: Handler | null = null;
/** The mock backend registers itself here when VITE_USE_MOCKS=true. */
export function setMockHandler(h: Handler) {
  mockHandler = h;
}

/** One place for base URLs, JWT, JSON and readable errors. Every API module goes through this. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (mockHandler) return (await mockHandler(path, init)) as T;
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

export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) });

/** Saves a Blob as a file download. */
export function saveBlob(blob: Blob, name: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
