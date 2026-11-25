import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PENDING } from "../api/endpoints";
import type { User } from "../api/types";
import { AuthProvider } from "../lib/auth";
import { ToastProvider } from "../lib/toast";
import { UsersTab } from "../pages/Admin";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const user = (id: number, email: string, fullName: string, role: User["role"], extra: Partial<User> = {}): User => ({
  id, username: email, email, fullName, role, enabled: true, lockedUntil: null, lastLoginAt: null, ...extra,
});

describe("Admin > Users", () => {
  let list: User[];
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    list = [
      user(1, "admin@corrosionwatch.app", "Avery Admin", "ADMIN", { lastLoginAt: new Date().toISOString() }),
      user(2, "tech@corrosionwatch.app", "Tariq Technician", "TECHNICIAN", { lockedUntil: new Date(Date.now() + 600e3).toISOString() }),
      user(3, "manager@corrosionwatch.app", "Morgan Manager", "MANAGER"),
    ];
    fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? "GET";
      if (url.endsWith("/api/v1/admin/users") && method === "GET") return json(200, list);
      const unlock = url.match(/\/api\/v1\/admin\/users\/(\d+)\/unlock$/);
      if (unlock && method === "POST") {
        const u = list.find((x) => x.id === +unlock[1])!;
        u.lockedUntil = null;
        return json(200, u);
      }
      const patch = url.match(/\/api\/v1\/admin\/users\/(\d+)$/);
      if (patch && method === "PATCH") {
        const u = list.find((x) => x.id === +patch[1])!;
        const { role } = JSON.parse(String(init.body));
        if (u.role === "ADMIN") return json(409, { error: "ROLE_CHANGE_REFUSED", message: "This is the last administrator. Make someone else an administrator before changing this role." });
        u.role = role;
        return json(200, u);
      }
      return json(404, { message: "no route" });
    });
    vi.stubGlobal("fetch", fetchMock);
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "admin@corrosionwatch.app", fullName: "Avery Admin", role: "ADMIN" }));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });

  const show = () => render(<AuthProvider><ToastProvider><UsersTab /></ToastProvider></AuthProvider>);
  const row = (name: string) => screen.getByText(name).closest("tr") as HTMLElement;
  const calls = (method: string) => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? "GET") === method);

  it("is no longer a pending endpoint", () => {
    expect(Object.keys(PENDING)).not.toContain("users");
  });

  it("lists the real users with status, and the signed-in admin's own role is not editable", async () => {
    show();
    expect(await screen.findByText("Tariq Technician")).toBeTruthy();
    expect(within(row("Tariq Technician")).getByText("Locked")).toBeTruthy();
    expect(within(row("Morgan Manager")).getByText("Active")).toBeTruthy();
    expect(within(row("Morgan Manager")).getByText("Never")).toBeTruthy();
    expect(within(row("Avery Admin")).getByText("(you)")).toBeTruthy();
    expect(within(row("Avery Admin")).queryByRole("button", { name: /role for/i })).toBeNull();
    expect(within(row("Morgan Manager")).queryByRole("button", { name: "Unlock" })).toBeNull();
  });

  it("unlocks a locked user and shows a toast", async () => {
    show();
    await screen.findByText("Tariq Technician");
    fireEvent.click(within(row("Tariq Technician")).getByRole("button", { name: "Unlock" }));
    expect(await screen.findByText("Tariq Technician can sign in again")).toBeTruthy();
    await waitFor(() => expect(within(row("Tariq Technician")).getByText("Active")).toBeTruthy());
    expect(calls("POST")[0][0]).toMatch(/\/api\/v1\/admin\/users\/2\/unlock$/);
  });

  it("asks for confirmation before saving a role, then saves it and shows a toast", async () => {
    show();
    await screen.findByText("Tariq Technician");
    fireEvent.click(screen.getByRole("button", { name: "Role for Tariq Technician" }));
    fireEvent.click(screen.getByRole("option", { name: "Operator" }));

    const dialog = screen.getByRole("dialog", { name: "Change role?" });
    expect(within(dialog).getByText("Tariq Technician: Technician to Operator.")).toBeTruthy();
    expect(calls("PATCH")).toHaveLength(0);

    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Change role" })); });
    expect(await screen.findByText("Tariq Technician is now operator")).toBeTruthy();
    expect(JSON.parse(String(calls("PATCH")[0][1].body))).toEqual({ role: "OPERATOR" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("cancelling the confirmation saves nothing", async () => {
    show();
    await screen.findByText("Morgan Manager");
    fireEvent.click(screen.getByRole("button", { name: "Role for Morgan Manager" }));
    fireEvent.click(screen.getByRole("option", { name: "Admin" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(calls("PATCH")).toHaveLength(0);
  });

  it("shows the backend's 409 message when a change is refused", async () => {
    list.push(user(4, "second.admin@corrosionwatch.app", "Sam Second", "ADMIN"));
    show();
    await screen.findByText("Sam Second");
    fireEvent.click(screen.getByRole("button", { name: "Role for Sam Second" }));
    fireEvent.click(screen.getByRole("option", { name: "Manager" }));
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Change role" })); });
    expect(await screen.findByText(/This is the last administrator/)).toBeTruthy();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("shows an error state with retry when the list fails", async () => {
    fetchMock.mockResolvedValueOnce(json(500, { message: "Database unavailable" }));
    show();
    expect(await screen.findByText("Database unavailable")).toBeTruthy();
  });

  it("shows an empty state when there are no users", async () => {
    list = [];
    show();
    expect(await screen.findByText("No users yet")).toBeTruthy();
  });
});
