import { createElement } from "react";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PENDING, alerts, assessments, equipmentStatus, profiles, sensorsHealth, users } from "../api/endpoints";
import { ApiError, NotBuiltError, api, isSample, type Pending } from "../lib/api";
import { config } from "../lib/config";
import { ErrorState } from "../components/ui";
import { MAX_FAILED_ATTEMPTS, StompClient, retryDelay } from "../lib/stomp";

const respond = (status: number, body: unknown = { message: "x" }) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

// An example of a pending endpoint: nothing is pending in src/api/endpoints.ts now, but the mechanism stays and these
// tests keep covering it. The path is one the mock backend answers, for the sample-data test.
const HISTORY: Pending = { key: "history", what: "Equipment history", endpoint: "GET /api/v1/equipment/{id}/assessments", owner: "Aayushee" };
const HISTORY_PATH = "/api/v1/equipment/1/assessments?minutes=120";

describe("endpoints that aren't built yet", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    config.mockFallback = false;
  });
  afterEach(() => vi.unstubAllGlobals());

  it("turns a 404 into NotBuiltError with the endpoint and owner, then stops requesting it", async () => {
    fetchMock.mockResolvedValue(respond(404));
    const err = (await api(HISTORY_PATH, {}, HISTORY).catch((e) => e)) as NotBuiltError;
    expect(err).toBeInstanceOf(NotBuiltError);
    expect(err.message).toBe("Equipment history isn't available yet. It needs GET /api/v1/equipment/{id}/assessments (owner: Aayushee).");
    await expect(api(HISTORY_PATH, {}, HISTORY)).rejects.toBeInstanceOf(NotBuiltError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("treats 501 the same way", async () => {
    fetchMock.mockResolvedValue(respond(501));
    // the test above already marked HISTORY not built, so use a fresh entry.
    const fresh = { ...HISTORY, key: "history-501" };
    await expect(api("/api/v1/equipment/1/assessments", {}, fresh)).rejects.toMatchObject({ status: 501, pending: fresh });
  });

  it("never treats a real (platform) endpoint's 404 as not built, and never falls back for it", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404, { message: "No such sensor" }));
    const err = await sensorsHealth().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(NotBuiltError);
    expect(err.message).toBe("No such sensor");
  });

  it("with VITE_MOCK_FALLBACK answers a not-built endpoint from the mock and marks the data as sample", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404));
    const list = await api<unknown[]>(HISTORY_PATH, {}, HISTORY);
    expect(Array.isArray(list)).toBe(true);
    expect(isSample(list)).toBe(true);
  });

  it("treats equipment history as built: a 404 is a real error, never sample data", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404, { message: "There is no equipment with id 99." }));
    const err = await assessments(99, 60).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(NotBuiltError);
    expect(err.message).toBe("There is no equipment with id 99.");
    expect(isSample(err)).toBe(false);
    expect(PENDING).not.toHaveProperty("assessments");
  });

  it("treats equipment profiles as built: a 404 is a real error, never sample data", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404, { message: "No such path" }));
    const err = await profiles().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(NotBuiltError);
    expect(isSample(err)).toBe(false);
    expect(PENDING).not.toHaveProperty("profiles");
  });

  it("treats equipment status as built: a 404 is a real error, never sample data", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404, { message: "No such path" }));
    const err = await equipmentStatus().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(NotBuiltError);
  });

  it("treats the alert endpoints as built: a 404 is a real error, never sample data", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404, { message: "Alert 99 not found." }));
    const err = await alerts("ALL", 10).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(NotBuiltError);
    expect(err.message).toBe("Alert 99 not found.");
  });

  it("treats the admin user list as built: a 404 is a real error, never sample data", async () => {
    config.mockFallback = true;
    fetchMock.mockResolvedValue(respond(404, { message: "No such path" }));
    const err = await users().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(NotBuiltError);
    expect(isSample(err)).toBe(false);
    expect(PENDING).not.toHaveProperty("users");
  });

  it("keeps real data unmarked", async () => {
    fetchMock.mockResolvedValue(respond(200, [{ sensorId: 1 }]));
    expect(isSample(await sensorsHealth())).toBe(false);
  });
});

describe("not built yet panel", () => {
  it("shows the calm panel with endpoint and owner instead of the error state", () => {
    render(createElement(ErrorState, { error: new NotBuiltError(404, HISTORY), onRetry: () => {} }));
    expect(screen.getByText("Not built yet")).toBeTruthy();
    expect(screen.getByText(/It needs GET \/api\/v1\/equipment\/\{id\}\/assessments \(owner: Aayushee\)/)).toBeTruthy();
    expect(screen.queryByText("Could not load this page")).toBeNull();
  });
});

describe("WebSocket reconnect", () => {
  it("backs off 3 s, doubling to 60 s", () => {
    expect([1, 2, 3, 4, 5, 6, 7].map(retryDelay)).toEqual([3000, 6000, 12000, 24000, 48000, 60000, 60000]);
  });

  it("stops after 5 failed attempts in a row", () => {
    vi.useFakeTimers();
    let opened = 0;
    class FailingSocket {
      onopen: (() => void) | null = null;
      onmessage: ((e: { data: string }) => void) | null = null;
      onclose: (() => void) | null = null;
      onerror: (() => void) | null = null;
      constructor() {
        opened++;
        setTimeout(() => this.onclose?.(), 0);
      }
      send() {}
      close() {}
    }
    vi.stubGlobal("WebSocket", FailingSocket);
    new StompClient("ws://nowhere/ws").start();
    vi.advanceTimersByTime(10 * 60_000);
    expect(opened).toBe(MAX_FAILED_ATTEMPTS);
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });
});
