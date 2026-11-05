import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore } from "react";
import { isNotBuilt, isSample } from "./api";

// Page queries currently showing sample data (VITE_MOCK_FALLBACK), so the Shell can badge the page.
const sampleQueries = new Set<string>();
const listeners = new Set<() => void>();
function setSampleQuery(id: string, on: boolean) {
  if (on === sampleQueries.has(id)) return;
  if (on) sampleQueries.add(id);
  else sampleQueries.delete(id);
  listeners.forEach((l) => l());
}
/** True while any mounted page query shows sample data instead of a real endpoint's. */
export function usePageUsesSample() {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => void listeners.delete(l); },
    () => sampleQueries.size > 0,
  );
}

/**
 * Minimal data-fetching hook: first load shows a skeleton (loading), later refreshes are silent.
 * `pollMs` refreshes in the background; `reload()` refetches on demand (after an action or a live event).
 * A 404/501 (endpoint not built yet) stops the polling until the page is reloaded.
 * `opts.ambient`: the query belongs to the layout (sidebar badge), not the page, so it doesn't badge the page as sample.
 */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[], pollMs?: number, opts: { ambient?: boolean } = {}) {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [sample, setSample] = useState(false);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const id = useId();
  const ambient = !!opts.ambient;

  const stopPolling = () => {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
  };

  const reload = useCallback(async () => {
    try {
      const value = await fnRef.current();
      setData(value);
      setError(null);
      setSample(isSample(value));
    } catch (e) {
      if (isNotBuilt(e)) stopPolling();
      setError(e as Error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setLoading(true);
    setData(undefined);
    reload();
    if (!pollMs) return;
    timer.current = setInterval(() => {
      if (!document.hidden) reload();
    }, pollMs);
    return stopPolling;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    if (ambient) return;
    setSampleQuery(id, sample);
    return () => setSampleQuery(id, false);
  }, [id, sample, ambient]);

  return { data, error, loading, reload, setData, sample };
}
