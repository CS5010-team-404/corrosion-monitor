import { createElement } from "react";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PENDING, alerts, equipmentStatus, sensorsHealth, users } from "../api/endpoints";
import { ApiError, NotBuiltError, isSample } from "../lib/api";
import { config } from "../lib/config";
import { ErrorState } from "../components/ui";
import { MAX_FAILED_ATTEMPTS, StompClient, retryDelay } from "../lib/stomp";

const respond = (status: number, body: unknown = { message: "x" }) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

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
    const err = await equipmentStatus().catch((e) => e);
    expect(err).toBeInstanceOf(NotBuiltError);
    expect(err.message).toBe("Equipment status isn't available yet. It needs GET /api/v1/equipment/status (owner: Aayushee).");
    await expect(equipmentStatus()).rejects.toBeInstanceOf(NotBuiltError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("treats 501 the same way", async () => {
    fetchMock.mockResolvedValue(respond(501));
    await expect(users()).rejects.toMatchObject({ status: 501, pending: PENDING.users });
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
    const list = await alerts("ALL", 10);
    expect(Array.isArray(list)).toBe(true);
    expect(isSample(list)).toBe(true);
  });

  it("keeps real data unmarked", async () => {
    fetchMock.mockResolvedValue(respond(200, [{ sensorId: 1 }]));
    expect(isSample(await sensorsHealth())).toBe(false);
  });
});

describe("not built yet panel", () => {
  it("shows the calm panel with endpoint and owner instead of the error state", () => {
    render(createElement(ErrorState, { error: new NotBuiltError(404, PENDING.reportSummary), onRetry: () => {} }));
    expect(screen.getByText("Not built yet")).toBeTruthy();
    expect(screen.getByText(/It needs GET \/api\/v1\/reports\/summary \(owner: Aayushee with Dumebi\)/)).toBeTruthy();
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
