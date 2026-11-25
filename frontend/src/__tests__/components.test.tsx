import { render, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { NotBuilt } from "../components/ui";
import { NotBuiltError, setMockHandler, type Pending } from "../lib/api";
import { config } from "../lib/config";
import { AuthProvider } from "../lib/auth";
import { Router } from "../lib/router";
import { ToastProvider } from "../lib/toast";
import { Admin } from "../pages/Admin";
import { Equipment } from "../pages/Equipment";
import { Reports } from "../pages/Reports";

// DataTable's tests live in datatable.test.tsx.

// An example of a pending endpoint for the panel; nothing is pending in src/api/endpoints.ts now.
const HISTORY: Pending = { key: "history", what: "Equipment history", endpoint: "GET /api/v1/equipment/{id}/assessments", owner: "Aayushee" };

describe("NotBuilt panel", () => {
  beforeAll(() => {
    config.useMocks = false;
    config.mockFallback = false;
    setMockHandler(null as never);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("names the missing endpoint and its owner", () => {
    render(<NotBuilt error={new NotBuiltError(404, HISTORY)} />);
    expect(screen.getByText("Not built yet")).toBeTruthy();
    expect(screen.getByText(/It needs GET \/api\/v1\/equipment\/\{id\}\/assessments \(owner: Aayushee\)/)).toBeTruthy();
  });

  // The Equipment page shows its history panel for any 404 or 501 from GET /api/v1/equipment/{id}/assessments (an
  // older backend that doesn't have it); profiles, users and reports are built, so their 404 is a real error.
  it("an Equipment page whose history endpoint answers 404 shows the panel, not an error", async () => {
    const status = [{ equipmentId: 1, equipmentName: "Pump P-101", locationName: "Hall A", profileName: "Carbon steel", profileKind: "plc",
      riskLevel: "NORMAL", riskIndex: 0.1, temperatureC: 20, humidityPct: 50, surfaceTempC: 19, dewPointC: 9, marginC: 10,
      forecastMargin15C: null, forecastMargin30C: null, forecastMargin60C: null, predictedCondensationAt: null, updatedAt: new Date().toISOString(),
      marginWarningC: 3, marginCriticalC: 1, rhWarningPct: 70, rhCriticalPct: 85, tempMinC: -10, tempMaxC: 60, surfaceOffsetC: 1,
      sensorId: null, deviceId: null, trusted: true }];
    const json = (body: unknown, code = 200) => new Response(JSON.stringify(body), { status: code, headers: { "Content-Type": "application/json" } });
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string) =>
      Promise.resolve(String(url).includes("/assessments") ? json({ message: "x" }, 404) : String(url).includes("/equipment/status") ? json(status) : json([]))));
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "manager@corrosionwatch.app", fullName: "Morgan Manager", role: "MANAGER" }));
    history.replaceState(null, "", "/equipment/1");
    render(<ToastProvider><AuthProvider><Router><Equipment id={1} /></Router></AuthProvider></ToastProvider>);
    expect(await screen.findByText("Not built yet", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.queryByText("Could not load this page")).toBeNull();
  });

  it("a built page (Admin > Equipment profiles) shows a real error for a 404, never the panel", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "x" }), { status: 404, headers: { "Content-Type": "application/json" } })));
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "admin@corrosionwatch.app", fullName: "Avery Admin", role: "ADMIN" }));
    history.replaceState(null, "", "/admin?tab=profiles");
    render(<ToastProvider><AuthProvider><Router><Admin /></Router></AuthProvider></ToastProvider>);
    expect(await screen.findByText("Could not load this page", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.queryByText("Not built yet")).toBeNull();
  });

  it("a built page (Admin > Users) shows a real error for a 404, never the panel", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "x" }), { status: 404, headers: { "Content-Type": "application/json" } })));
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "admin@corrosionwatch.app", fullName: "Avery Admin", role: "ADMIN" }));
    history.replaceState(null, "", "/admin?tab=users");
    render(<ToastProvider><AuthProvider><Router><Admin /></Router></AuthProvider></ToastProvider>);
    expect(await screen.findByText("Could not load this page", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.queryByText("Not built yet")).toBeNull();
  });

  it("a built page (Reports) shows a real error for a 404, never the panel", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "x" }), { status: 404, headers: { "Content-Type": "application/json" } })));
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "manager@corrosionwatch.app", fullName: "Morgan Manager", role: "MANAGER" }));
    history.replaceState(null, "", "/reports");
    render(<ToastProvider><AuthProvider><Router><Reports /></Router></AuthProvider></ToastProvider>);
    expect(await screen.findByText("Could not load this page", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.queryByText("Not built yet")).toBeNull();
  });
});
