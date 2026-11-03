import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { tokenStore } from "./api";
import { login as apiLogin } from "../api/endpoints";
import type { Role, Session } from "../api/types";

const KEY = "cw.session";
interface AuthValue {
  session: Session | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  can: (...roles: Role[]) => boolean;
  switchRole?: (role: Role) => void;
}
const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children, allowRoleSwitch }: { children: ReactNode; allowRoleSwitch?: boolean }) {
  const [session, setSession] = useState<Session | null>(() => {
    const raw = sessionStorage.getItem(KEY);
    return raw && tokenStore.get() ? (JSON.parse(raw) as Session) : null;
  });
  useEffect(() => {
    const off = () => setSession(null);
    window.addEventListener("cw:unauthorized", off);
    return () => window.removeEventListener("cw:unauthorized", off);
  }, []);
  const value = useMemo<AuthValue>(() => ({
    session,
    async login(username, password) {
      const r = await apiLogin(username, password);
      tokenStore.set(r.token);
      const s: Session = { username: r.username, fullName: r.fullName, role: r.role };
      sessionStorage.setItem(KEY, JSON.stringify(s));
      setSession(s);
    },
    logout() {
      tokenStore.clear();
      sessionStorage.removeItem(KEY);
      setSession(null);
    },
    can: (...roles) => !!session && roles.includes(session.role),
    switchRole: allowRoleSwitch
      ? (role) => {
          if (!session) return;
          const s = { ...session, role };
          sessionStorage.setItem(KEY, JSON.stringify(s));
          setSession(s);
        }
      : undefined,
  }), [session, allowRoleSwitch]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth outside AuthProvider");
  return ctx;
}
