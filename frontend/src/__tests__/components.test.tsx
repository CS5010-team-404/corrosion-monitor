import { render, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { NotBuilt } from "../components/ui";
import { PENDING } from "../api/endpoints";
import { NotBuiltError, setMockHandler } from "../lib/api";
import { config } from "../lib/config";
import { AuthProvider } from "../lib/auth";
import { Router } from "../lib/router";
import { ToastProvider } from "../lib/toast";
import { Admin } from "../pages/Admin";
import { Reports } from "../pages/Reports";

// DataTable's tests live in datatable.test.tsx.

describe("NotBuilt panel", () => {
  beforeAll(() => {
    config.useMocks = false;
    config.mockFallback = false;
    setMockHandler(null as never);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("names the missing endpoint and its owner", () => {
    render(<NotBuilt error={new NotBuiltError(404, PENDING.users)} />);
    expect(screen.getByText("Not built yet")).toBeTruthy();
    expect(screen.getByText(/It needs GET \/api\/v1\/admin\/users \(owner: team\)/)).toBeTruthy();
  });

  // Administration > Users still waits on GET /api/v1/admin/users (PENDING.users); reports are built now, so their 404 is a real error.
  it("a page whose endpoint answers 404 shows the panel, not an error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "x" }), { status: 404, headers: { "Content-Type": "application/json" } })));
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "admin@corrosionwatch.app", fullName: "Avery Admin", role: "ADMIN" }));
    history.replaceState(null, "", "/admin?tab=users");
    render(<ToastProvider><AuthProvider><Router><Admin /></Router></AuthProvider></ToastProvider>);
    expect(await screen.findByText("Not built yet", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.getByText(/It needs GET \/api\/v1\/admin\/users/)).toBeTruthy();
    expect(screen.queryByText("Could not load this page")).toBeNull();
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
