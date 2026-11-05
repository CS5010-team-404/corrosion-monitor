import { createContext, useContext, useEffect, useState, type MouseEvent, type ReactNode } from "react";

// A tiny history router: enough for eight pages and two route params, without another dependency.
const RouterContext = createContext<{ path: string; navigate: (to: string) => void }>({ path: "/", navigate: () => {} });

export function Router({ children }: { children: ReactNode }) {
  const [path, setPath] = useState(location.pathname + location.search);
  useEffect(() => {
    const on = () => setPath(location.pathname + location.search);
    window.addEventListener("popstate", on);
    return () => window.removeEventListener("popstate", on);
  }, []);
  const navigate = (to: string) => {
    if (to === location.pathname + location.search) return;
    history.pushState(null, "", to);
    setPath(to);
    window.scrollTo(0, 0);
  };
  return <RouterContext.Provider value={{ path, navigate }}>{children}</RouterContext.Provider>;
}

export const useRouter = () => useContext(RouterContext);

/** Matches "/equipment/:id" style patterns. Returns params or null. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const [clean] = path.split("?");
  const p = pattern.split("/").filter(Boolean);
  const a = clean.split("/").filter(Boolean);
  if (p.length !== a.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < p.length; i++) {
    if (p[i].startsWith(":")) params[p[i].slice(1)] = decodeURIComponent(a[i]);
    else if (p[i] !== a[i]) return null;
  }
  return params;
}

export function Link({ to, children, className, ...rest }: { to: string; children: ReactNode; className?: string; [k: string]: unknown }) {
  const { navigate } = useRouter();
  const click = (e: MouseEvent) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    navigate(to);
  };
  return <a href={to} className={className} onClick={click} {...rest}>{children}</a>;
}
