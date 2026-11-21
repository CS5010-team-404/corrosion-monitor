import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { DataTable, type Column } from "../components/DataTable";
import { NotBuilt } from "../components/ui";
import { PENDING } from "../api/endpoints";
import { NotBuiltError, setMockHandler } from "../lib/api";
import { config } from "../lib/config";
import { AuthProvider } from "../lib/auth";
import { Router } from "../lib/router";
import { ToastProvider } from "../lib/toast";
import { Reports } from "../pages/Reports";

interface Row { id: number; name: string; score: number }
const ROWS: Row[] = Array.from({ length: 23 }, (_, i) => ({ id: i + 1, name: `item ${String(i + 1).padStart(2, "0")}`, score: (i * 7) % 23 }));
const COLUMNS: Column<Row>[] = [
  { key: "name", label: "Name", sortable: true, sortValue: (r) => r.name, render: (r) => r.name },
  { key: "score", label: "Score", sortable: true, right: true, sortValue: (r) => r.score, render: (r) => String(r.score) },
  { key: "note", label: "Note", render: () => "–" },
];
const firstCells = () => screen.getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell")[0].textContent);
const pager = () => document.querySelector(".pager .num")!.textContent;

describe("DataTable", () => {
  it("sorts by a column and flips the direction on a second click", () => {
    render(<DataTable rows={ROWS} columns={COLUMNS} initialSort={{ key: "name", dir: "asc" }} />);
    expect(firstCells().slice(0, 2)).toEqual(["item 01", "item 02"]);
    const score = screen.getByRole("columnheader", { name: /Score/ });
    fireEvent.click(score);
    expect(score.getAttribute("aria-sort")).toBe("ascending");
    expect(screen.getAllByRole("row")[1].textContent).toContain("0");
    fireEvent.click(score);
    expect(score.getAttribute("aria-sort")).toBe("descending");
    expect(within(screen.getAllByRole("row")[1]).getAllByRole("cell")[1].textContent).toBe("22");
    fireEvent.click(screen.getByRole("columnheader", { name: "Note" }));   // not sortable: nothing changes
    expect(score.getAttribute("aria-sort")).toBe("descending");
  });

  it("pages with previous/next and numbers, and shows X–Y of Z", () => {
    render(<DataTable rows={ROWS} columns={COLUMNS} initialSort={{ key: "name", dir: "asc" }} />);
    expect(pager()).toBe("Showing 1–10 of 23");
    expect((screen.getByRole("button", { name: "Previous page" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    expect(pager()).toBe("Showing 11–20 of 23");
    expect(firstCells()[0]).toBe("item 11");
    fireEvent.click(screen.getByRole("button", { name: "3" }));
    expect(pager()).toBe("Showing 21–23 of 23");
    expect((screen.getByRole("button", { name: "Next page" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole("button", { name: "3" }).getAttribute("aria-current")).toBe("page");
  });

  it("changing the page size goes back to page 1 and keeps the sort", () => {
    render(<DataTable rows={ROWS} columns={COLUMNS} initialSort={{ key: "name", dir: "desc" }} />);
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    fireEvent.click(screen.getByRole("button", { name: "Rows per page" }));
    fireEvent.click(screen.getByRole("option", { name: "5 per page" }));
    expect(pager()).toBe("Showing 1–5 of 23");
    expect(firstCells()).toEqual(["item 23", "item 22", "item 21", "item 20", "item 19"]);
  });

  it("selects rows and offers bulk actions", () => {
    const bulk = vi.fn();
    render(<DataTable rows={ROWS} columns={COLUMNS} selectable bulk={(ids, clear) => <button onClick={() => { bulk(ids); clear(); }}>Do it</button>} />);
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all on page" }));
    expect(screen.getByText("10 selected")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Do it" }));
    expect(bulk).toHaveBeenCalledWith(ROWS.slice(0, 10).map((r) => r.id));
    expect(screen.queryByText(/selected/)).toBeNull();
  });

  it("shows an empty state", () => {
    render(<DataTable rows={[]} columns={COLUMNS} emptyTitle="No alerts" emptyText="All quiet." />);
    expect(screen.getByText("No alerts")).toBeTruthy();
    expect(pager()).toBe("No results");
  });
});

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

  it("a page whose endpoint answers 404 shows the panel, not an error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "x" }), { status: 404, headers: { "Content-Type": "application/json" } })));
    sessionStorage.setItem("cw.token", "t");
    sessionStorage.setItem("cw.session", JSON.stringify({ username: "manager@corrosionwatch.app", fullName: "Morgan Manager", role: "MANAGER" }));
    render(<ToastProvider><AuthProvider><Router><Reports /></Router></AuthProvider></ToastProvider>);
    expect(await screen.findByText("Not built yet", {}, { timeout: 4000 })).toBeTruthy();
    expect(screen.queryByText("Could not load this page")).toBeNull();
  });
});
