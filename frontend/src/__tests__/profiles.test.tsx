import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Profile, Role } from "../api/types";
import { AuthProvider } from "../lib/auth";
import { ToastProvider } from "../lib/toast";
import { ProfilesTab } from "../pages/Admin";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const IT: Profile = { id: 1, name: "IT equipment (ASHRAE)", rhWarningPct: 60, rhCriticalPct: 70, marginWarningC: 3, marginCriticalC: 1, tempMinC: 18, tempMaxC: 27, surfaceOffsetC: 1.5 };

describe("Admin > Equipment profiles", () => {
  let list: Profile[];
  let fetchMock: ReturnType<typeof vi.fn>;

  const signIn = (role: Role) => {
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "someone@corrosionwatch.app", fullName: "Some One", role }));
  };

  beforeEach(() => {
    list = [{ ...IT }, { ...IT, id: 2, name: "Telecom cabinet", rhCriticalPct: 75, surfaceOffsetC: 2 }];
    fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? "GET";
      if (url.endsWith("/api/v1/profiles") && method === "GET") return json(200, list);
      const put = url.match(/\/api\/v1\/profiles\/(\d+)$/);
      if (put && method === "PUT") {
        const b = JSON.parse(String(init.body));
        if (b.marginWarningC <= b.marginCriticalC) {
          return json(400, { error: "INVALID_PROFILE", message: "Warning margin must be greater than the critical margin.", problems: ["Warning margin must be greater than the critical margin."] });
        }
        const i = list.findIndex((x) => x.id === +put[1]);
        list[i] = { id: list[i].id, ...b };
        return json(200, list[i]);
      }
      return json(404, { message: "no route" });
    });
    vi.stubGlobal("fetch", fetchMock);
    signIn("ADMIN");
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });

  const show = () => render(<AuthProvider><ToastProvider><ProfilesTab /></ToastProvider></AuthProvider>);
  const puts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
  const dialog = () => screen.getByRole("dialog");
  const field = (label: RegExp) => within(dialog()).getByLabelText(label) as HTMLInputElement;

  it("lists profiles with the surface offset, and admins get an Edit button per profile", async () => {
    show();
    expect(await screen.findByText("Telecom cabinet")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Edit IT equipment (ASHRAE)" })).toBeTruthy();
    expect(screen.getAllByText("1.5 °C").length).toBeGreaterThan(0);
  });

  it("shows no edit button to someone who is not an admin", async () => {
    sessionStorage.clear();
    signIn("MANAGER");
    show();
    expect(await screen.findByText("Telecom cabinet")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^Edit / })).toBeNull();
  });

  it("asks for confirmation listing the changes, then saves with PUT and shows a toast", async () => {
    show();
    fireEvent.click(await screen.findByRole("button", { name: "Edit IT equipment (ASHRAE)" }));
    const review = within(dialog()).getByRole("button", { name: "Review changes" }) as HTMLButtonElement;
    expect(review.disabled).toBe(true); // nothing changed yet
    fireEvent.change(field(/RH critical/), { target: { value: "65" } });
    fireEvent.click(review);
    const row = within(dialog()).getByText("RH critical").closest("tr") as HTMLElement;
    expect(within(row).getByText("70 %")).toBeTruthy();
    expect(within(row).getByText("65 %")).toBeTruthy();
    expect(puts()).toHaveLength(0); // not saved before confirming
    fireEvent.click(within(dialog()).getByRole("button", { name: "Save profile" }));
    expect(await screen.findByText("IT equipment (ASHRAE) saved")).toBeTruthy();
    expect(puts()).toHaveLength(1);
    const [url, init] = puts()[0];
    expect(url).toMatch(/\/api\/v1\/profiles\/1$/);
    expect(JSON.parse(String(init.body))).toEqual({ name: "IT equipment (ASHRAE)", rhWarningPct: 60, rhCriticalPct: 65, marginWarningC: 3, marginCriticalC: 1, tempMinC: 18, tempMaxC: 27, surfaceOffsetC: 1.5 });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(await screen.findByText("65%")).toBeTruthy(); // reloaded list
  });

  it("shows the server's 400 message as it is and keeps the form open", async () => {
    show();
    fireEvent.click(await screen.findByRole("button", { name: "Edit IT equipment (ASHRAE)" }));
    fireEvent.change(field(/Margin warning/), { target: { value: "0.5" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Review changes" }));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Save profile" }));
    const alert = await within(dialog()).findByText(/Warning margin must be greater than the critical margin\./);
    expect(alert).toBeTruthy();
    expect(field(/Margin warning/).value).toBe("0.5"); // back on the form with the user's values
  });

  it("does not let an empty number through", async () => {
    show();
    fireEvent.click(await screen.findByRole("button", { name: "Edit IT equipment (ASHRAE)" }));
    fireEvent.change(field(/Surface offset/), { target: { value: "" } });
    expect(within(dialog()).getByText("Enter a number.")).toBeTruthy();
    expect((within(dialog()).getByRole("button", { name: "Review changes" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
