import { useState } from "react";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DataTable, type Column, type ServerPaging } from "../components/DataTable";

interface Row { id: number; name: string; group: string }
const ROWS: Row[] = Array.from({ length: 23 }, (_, i) => ({ id: i + 1, name: `item ${String(i + 1).padStart(2, "0")}`, group: i % 2 ? "odd" : "even" }));
const COLUMNS: Column<Row>[] = [
  { key: "name", label: "Name", sortable: true, sortValue: (r) => r.name, render: (r) => r.name },
  { key: "group", label: "Group", render: (r) => r.group },
];

/** A page around the table that owns a filter, like the real pages do. */
function Page({ tableId = "t1" }: { tableId?: string }) {
  const [group, setGroup] = useState("all");
  const rows = ROWS.filter((r) => group === "all" || r.group === group);
  return (
    <>
      <button onClick={() => setGroup("even")}>only even</button>
      <DataTable rows={rows} columns={COLUMNS} tableId={tableId} initialSort={{ key: "name", dir: "desc" }} />
    </>
  );
}

const names = () => screen.getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell")[0].textContent);
const chooseSize = (n: number) => {
  fireEvent.click(screen.getByRole("button", { name: "Rows per page" }));
  fireEvent.click(screen.getByRole("option", { name: `${n} per page` }));
};

beforeEach(() => localStorage.clear());
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("DataTable rows per page", () => {
  it("renders the size list outside the table card, so overflow: hidden can't clip it", () => {
    render(<Page />);
    fireEvent.click(screen.getByRole("button", { name: "Rows per page" }));
    const list = screen.getByRole("listbox", { name: "Rows per page" });
    expect(list.closest(".card")).toBeNull();
    expect(list.parentElement).toBe(document.body);
    expect(list.style.position).toBe("fixed");
  });

  it("goes back to page 1, keeps the sort and the filter, and shows X–Y of Z", () => {
    render(<Page />);
    fireEvent.click(screen.getByRole("button", { name: "only even" }));          // filter: 12 rows
    fireEvent.click(screen.getByRole("button", { name: "2" }));                  // page 2 of 10 per page
    expect(screen.getByText("Showing 11–12 of 12")).toBeTruthy();
    chooseSize(5);
    expect(screen.getByText("Showing 1–5 of 12")).toBeTruthy();                  // page 1, filter kept
    expect(names()).toEqual(["item 23", "item 21", "item 19", "item 17", "item 15"]);  // still name descending
    expect(screen.getByRole("button", { name: "Rows per page" }).textContent).toContain("5 per page");
    expect(screen.queryByRole("listbox")).toBeNull();                            // the list closed
  });

  it("remembers the choice per table", () => {
    const first = render(<Page tableId="alerts" />);
    chooseSize(20);
    first.unmount();
    render(<Page tableId="alerts" />);
    expect(screen.getByText("Showing 1–20 of 23")).toBeTruthy();
    cleanup();
    render(<Page tableId="sensors" />);                                          // another table keeps its default
    expect(screen.getByText("Showing 1–10 of 23")).toBeTruthy();
  });

  it("still works when storage is unavailable", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    render(<Page />);
    chooseSize(8);
    expect(screen.getByText("Showing 1–8 of 23")).toBeTruthy();
    vi.restoreAllMocks();
  });
});

describe("DataTable server paging", () => {
  it("shows the server's total, asks the parent for pages and sizes, and doesn't sort one page locally", () => {
    const onPage = vi.fn(), onSize = vi.fn();
    const server: ServerPaging = { total: 1234, page: 2, size: 10, onPage, onSize };
    render(<DataTable rows={ROWS.slice(0, 10)} columns={COLUMNS} server={server} />);
    expect(screen.getByText("Showing 11–20 of 1,234")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    expect(onPage).toHaveBeenCalledWith(3);
    chooseSize(50);
    expect(onSize).toHaveBeenCalledWith(50);
    expect(onPage).toHaveBeenLastCalledWith(1);
    fireEvent.click(screen.getByRole("columnheader", { name: /Name/ }));
    expect(names()[0]).toBe("item 01");                                          // server order kept
  });
});
