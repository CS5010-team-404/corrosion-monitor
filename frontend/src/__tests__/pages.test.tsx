import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { App } from "../App";
import { config } from "../lib/config";
import { installMocks } from "../mock/server";
import { sim } from "../mock/sim";

/**
 * Every page rendered for real (App, router, shell, data loading, tables) against the built-in mock backend, the same
 * one `npm run dev:mock` uses. Checks that each page shows data, and the role rules the UI enforces.
 */
const WAIT = { timeout: 4000 };

beforeAll(() => {
  window.scrollTo = () => {};   // jsdom has no layout; navigation scrolls to the top
  config.useMocks = true;
  installMocks(() => {});
});

const NAMES = { TECHNICIAN: "Tariq Technician", OPERATOR: "Olivia Operator", MANAGER: "Morgan Manager", ADMIN: "Avery Admin" } as const;
type Role = keyof typeof NAMES;

function open(path: string, role: Role) {
  sessionStorage.setItem("cw.token", `mock.${role}`);
  sessionStorage.setItem("cw.session", JSON.stringify({ username: `${role.toLowerCase()}@corrosionwatch.app`, fullName: NAMES[role], role }));
  history.replaceState(null, "", path);
  return render(<App />);
}

beforeEach(() => sessionStorage.clear());

describe("pages with data", () => {
  it("Overview: greeting, conditions card, locations and equipment", async () => {
    open("/", "ADMIN");
    expect(await screen.findByRole("heading", { level: 1, name: /, Avery$/ }, WAIT)).toBeTruthy();
    expect(await screen.findByText("Locations", {}, WAIT)).toBeTruthy();
    expect(await screen.findByText("Rack A1", {}, WAIT)).toBeTruthy();
    expect(screen.getAllByText("Server Room A", { exact: false }).length).toBeGreaterThan(0);
  });

  it("Overview table view lists every item", async () => {
    open("/", "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: /Table/ }, WAIT));
    const table = await screen.findByRole("table", {}, WAIT);
    await waitFor(() => expect(within(table).getAllByRole("row").length).toBeGreaterThan(2), WAIT);
  });

  it("Equipment page: readings, risk and sensor", async () => {
    open("/equipment/1", "ADMIN");
    expect(await screen.findByText("Readings", {}, WAIT)).toBeTruthy();
    expect(screen.getByText("Risk breakdown")).toBeTruthy();
    expect(screen.getByText("Sensor and alerts")).toBeTruthy();
    expect(screen.getAllByText("Rack A1").length).toBeGreaterThan(0);
  });

  it("Alerts: history and tabs", async () => {
    open("/alerts", "ADMIN");
    expect(await screen.findByRole("heading", { level: 1, name: "Alerts" }, WAIT)).toBeTruthy();
    fireEvent.click(screen.getAllByRole("tab").at(-1)!);   // All
    expect(await screen.findAllByText(/Sensor offline|Condensation risk/, {}, WAIT)).not.toHaveLength(0);
  });

  it("Sensor health lists the simulated devices", async () => {
    open("/health", "ADMIN");
    expect(await screen.findAllByText(/SIM-0\d\d/, {}, WAIT)).not.toHaveLength(0);
  });

  it("Audit log with entries", async () => {
    open("/audit", "ADMIN");
    expect(await screen.findByRole("heading", { level: 1, name: "Audit log" }, WAIT)).toBeTruthy();
    expect(await screen.findAllByText(/Alert opened|Alert resolved|User login/, {}, WAIT)).not.toHaveLength(0);
  });

  it("Reports summary", async () => {
    open("/reports", "MANAGER");
    expect(await screen.findByRole("heading", { level: 1, name: /Reports/ }, WAIT)).toBeTruthy();
    expect(await screen.findAllByText(/Rack A1/, {}, WAIT)).not.toHaveLength(0);
  });

  it("Administration tabs", async () => {
    open("/admin?tab=simulator", "ADMIN");
    expect(await screen.findAllByText("SIM-001", {}, WAIT)).not.toHaveLength(0);
    for (const tab of ["Sensors", "Equipment profiles", "Users"]) {
      fireEvent.click(screen.getByRole("tab", { name: tab }));
      await waitFor(() => expect(screen.getAllByRole("row").length).toBeGreaterThan(1), WAIT);
    }
    expect(await screen.findByText("Avery Admin", { selector: ".cell-main" }, WAIT)).toBeTruthy();
  });
});

describe("role rules in the UI", () => {
  const OPEN_ID = 9001;
  beforeEach(() => {
    // sim.js is plain JavaScript, so its state has no useful type here.
    const S = sim.S as unknown as { alerts: { id: number }[] };
    S.alerts = S.alerts.filter((a) => a.id !== OPEN_ID);
    S.alerts.unshift({ id: OPEN_ID, key: "TEST:1", sev: "CRITICAL", type: "SENSOR_STUCK", eqId: 1, title: "Sensor stuck: SIM-001",
      msg: "The last 20 readings did not change.", status: "OPEN", opened: sim.now(), actions: [] } as { id: number });
  });

  it("a technician can acknowledge, resolve and log actions", async () => {
    open(`/alerts?alert=${OPEN_ID}`, "TECHNICIAN");
    const drawer = await screen.findByRole("dialog", { name: "Sensor stuck: SIM-001" }, WAIT);
    await within(drawer).findByText("Timeline", {}, WAIT);
    expect(within(drawer).getByRole("button", { name: /Acknowledge/ })).toBeTruthy();
    expect(within(drawer).getByRole("button", { name: "Resolve" })).toBeTruthy();
    expect(within(drawer).getByRole("button", { name: "Log action" })).toBeTruthy();
    fireEvent.click(within(drawer).getByRole("button", { name: /Acknowledge/ }));
    expect(await within(drawer).findByText(/Acknowledged by/, {}, WAIT)).toBeTruthy();
    expect(within(drawer).queryByRole("button", { name: /Acknowledge/ })).toBeNull();   // only from OPEN
  });

  it("a manager sees the alert but no action buttons", async () => {
    open(`/alerts?alert=${OPEN_ID}`, "MANAGER");
    const drawer = await screen.findByRole("dialog", { name: "Sensor stuck: SIM-001" }, WAIT);
    await within(drawer).findByText("Timeline", {}, WAIT);
    expect(within(drawer).queryByRole("button", { name: /Acknowledge/ })).toBeNull();
    expect(within(drawer).queryByRole("button", { name: "Resolve" })).toBeNull();
    expect(within(drawer).queryByRole("button", { name: "Log action" })).toBeNull();
    expect(within(drawer).getByText("Technicians and admins act on alerts.")).toBeTruthy();
  });

  it("pages outside a role are refused, and the menu hides them", async () => {
    open("/audit", "TECHNICIAN");
    expect(await screen.findByText("Your role does not have access to this page.", {}, WAIT)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /Audit log/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /Administration/ })).toBeNull();
  });
});
