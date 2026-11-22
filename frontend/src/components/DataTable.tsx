import { useMemo, useState, type ReactNode } from "react";
import { Dropdown } from "./Dropdown";
import { Icon } from "./Icon";
import { Empty } from "./ui";

export const PAGE_SIZES = [5, 8, 10, 20, 50];

/**
 * Rows per page, remembered per table (tableId) in this browser. Storage may be unavailable (private window, blocked
 * site data), so every access is guarded and the default is used instead.
 */
export function useTableSize(tableId: string | undefined, fallback: number) {
  const key = tableId ? `cw.table.${tableId}.size` : null;
  const [size, setSize] = useState(() => {
    try {
      const v = key ? Number(localStorage.getItem(key)) : NaN;
      return PAGE_SIZES.includes(v) ? v : fallback;
    } catch { return fallback; }
  });
  const choose = (n: number) => {
    setSize(n);
    try { if (key) localStorage.setItem(key, String(n)); } catch { /* not remembered, still applied */ }
  };
  return [size, choose] as const;
}

/** Server-side paging: rows are already one page; the parent fetches page/size and gives the total. */
export interface ServerPaging { total: number; page: number; size: number; onPage: (page: number) => void; onSize: (size: number) => void }

export interface Column<R> { key: string; label: string; sortable?: boolean; right?: boolean; width?: string; render: (row: R) => ReactNode; sortValue?: (row: R) => string | number }

/**
 * Client-side data table: sortable headers, pagination with page size, optional row selection with bulk actions,
 * a toolbar slot for search and filters, empty state, and stacked cards on phones (CSS).
 * Page size is remembered per table when `tableId` is given. Changing it goes back to page 1 and keeps the sort
 * (filters live in the parent and are untouched). For very large tables (audit log), pass `server` for server-side
 * paging: rows are then one page, already in the server's order, so client-side sorting is turned off.
 */
export function DataTable<R extends { id: string | number }>({ rows, columns, tools, initialSort, pageSize = 10, tableId, server, onRowClick, selectable, bulk, isSelected, emptyTitle, emptyText }: {
  rows: R[]; columns: Column<R>[]; tools?: ReactNode; initialSort?: { key: string; dir: "asc" | "desc" }; pageSize?: number;
  tableId?: string; server?: ServerPaging;
  onRowClick?: (r: R) => void; selectable?: boolean; bulk?: (ids: (string | number)[], clear: () => void) => ReactNode;
  isSelected?: (r: R) => boolean; emptyTitle?: string; emptyText?: string;
}) {
  const [sort, setSort] = useState(initialSort ?? { key: columns[0].key, dir: "asc" as const });
  const [localPage, setLocalPage] = useState(1);
  const [localSize, chooseLocalSize] = useTableSize(tableId, pageSize);
  const [sel, setSel] = useState<Set<string | number>>(new Set());
  const size = server ? server.size : localSize;
  const page = server ? server.page : localPage;
  const setPage = server ? server.onPage : setLocalPage;
  const changeSize = (n: number) => {
    if (server) server.onSize(n); else chooseLocalSize(n);
    setPage(1);
  };

  const sorted = useMemo(() => {
    if (server) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col?.sortValue) return rows;
    const m = sort.dir === "asc" ? 1 : -1;
    return [...rows].sort((a, b) => { const x = col.sortValue!(a), y = col.sortValue!(b); return (x > y ? 1 : x < y ? -1 : 0) * m; });
  }, [rows, columns, sort, server]);
  const total = server ? server.total : sorted.length;
  const pages = Math.max(1, Math.ceil(total / size));
  const cur = Math.min(page, pages);
  const view = server ? sorted : sorted.slice((cur - 1) * size, cur * size);
  const from = total && view.length ? (cur - 1) * size + 1 : 0, to = server ? from + Math.max(0, view.length - 1) : Math.min(total, cur * size);
  const nums: (number | "…")[] = [];
  for (let i = 1; i <= pages; i++) if (i === 1 || i === pages || Math.abs(i - cur) <= 1) nums.push(i); else if (nums[nums.length - 1] !== "…") nums.push("…");
  const allSel = view.length > 0 && view.every((r) => sel.has(r.id));
  const toggle = (id: string | number) => setSel((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const canSort = (c: Column<R>) => !!c.sortable && !server;
  const clickSort = (c: Column<R>) => { if (!canSort(c)) return; setSort((s) => ({ key: c.key, dir: s.key === c.key && s.dir === "asc" ? "desc" : "asc" })); setPage(1); };

  return (
    <div className="card flush">
      {tools && <div className="dt-tools">{tools}</div>}
      {selectable && sel.size > 0 && bulk && <div className="bulk"><span className="num">{sel.size} selected</span>{bulk([...sel], () => setSel(new Set()))}<button className="link-btn" onClick={() => setSel(new Set())}>Clear</button></div>}
      <div className="table-wrap">
        <table className="dt">
          <thead><tr>
            {selectable && <th style={{ width: 44 }}><input type="checkbox" className="cb" checked={allSel} aria-label="Select all on page" onChange={() => setSel((s) => { const n = new Set(s); view.forEach((r) => allSel ? n.delete(r.id) : n.add(r.id)); return n; })} /></th>}
            {columns.map((c) => (
              <th key={c.key} className={`${canSort(c) ? "sortable" : ""} ${!server && sort.key === c.key ? "sorted" : ""} ${c.right ? "r" : ""}`} style={c.width ? { width: c.width } : undefined}
                onClick={() => clickSort(c)} aria-sort={!server && sort.key === c.key ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}>
                {c.label}{canSort(c) && <span className="si"><Icon name={sort.key === c.key ? (sort.dir === "asc" ? "up" : "down") : "sort"} /></span>}
              </th>
            ))}
          </tr></thead>
          <tbody>
            {view.length ? view.map((r) => (
              <tr key={r.id} className={`${onRowClick ? "click" : ""} ${isSelected?.(r) ? "sel" : ""}`} onClick={() => onRowClick?.(r)} tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={(e) => e.key === "Enter" && onRowClick?.(r)}>
                {selectable && <td style={{ width: 44 }} onClick={(e) => e.stopPropagation()}><input type="checkbox" className="cb" checked={sel.has(r.id)} onChange={() => toggle(r.id)} aria-label="Select row" /></td>}
                {columns.map((c) => <td key={c.key} className={c.right ? "r" : ""}>{c.render(r)}</td>)}
              </tr>
            )) : <tr><td colSpan={columns.length + (selectable ? 1 : 0)}><Empty title={emptyTitle ?? "Nothing matches"} text={emptyText ?? "Try another search or clear the filters."} /></td></tr>}
          </tbody>
        </table>
      </div>
      <div className="pager">
        <span className="num" aria-live="polite">{total && view.length ? `Showing ${from}–${to} of ${total.toLocaleString()}` : "No results"}</span>
        <div className="row">
          <Dropdown value={String(size)} options={PAGE_SIZES.map((n) => ({ value: String(n), label: `${n} per page` }))} onChange={(v) => changeSize(+v)} ariaLabel="Rows per page" right />
          <div className="pages">
            <button className="pg" disabled={cur <= 1} onClick={() => setPage(cur - 1)} aria-label="Previous page"><Icon name="chevL" /></button>
            {nums.map((n, i) => n === "…" ? <span key={`e${i}`} className="pg" style={{ cursor: "default" }}>…</span> : <button key={n} className="pg" aria-current={n === cur ? "page" : undefined} onClick={() => setPage(n)}>{n}</button>)}
            <button className="pg" disabled={cur >= pages} onClick={() => setPage(cur + 1)} aria-label="Next page"><Icon name="chevR" /></button>
          </div>
        </div>
      </div>
    </div>
  );
}

export function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return <div className="input-icon"><Icon name="search" /><input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={placeholder} /></div>;
}
