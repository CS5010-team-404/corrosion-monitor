import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Minimal data-fetching hook: first load shows a skeleton (loading), later refreshes are silent.
 * `pollMs` refreshes in the background; `reload()` refetches on demand (after an action or a live event).
 */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[], pollMs?: number) {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const reload = useCallback(async () => {
    try {
      const value = await fnRef.current();
      setData(value);
      setError(null);
    } catch (e) {
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
    const t = setInterval(() => {
      if (!document.hidden) reload();
    }, pollMs);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, error, loading, reload, setData };
}
