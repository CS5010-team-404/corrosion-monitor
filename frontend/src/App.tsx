import type { ReactNode } from "react";
import type { Role } from "./api/types";
import { Shell, NAV } from "./layout/Shell";
import { AuthProvider, useAuth } from "./lib/auth";
import { config } from "./lib/config";
import { match, Router, useRouter } from "./lib/router";
import { ToastProvider } from "./lib/toast";
import { Admin } from "./pages/Admin";
import { Alerts } from "./pages/Alerts";
import { Audit } from "./pages/Audit";
import { Equipment } from "./pages/Equipment";
import { Health } from "./pages/Health";
import { Login } from "./pages/Login";
import { Overview } from "./pages/Overview";
import { Reports } from "./pages/Reports";
import { ErrorState } from "./components/ui";

function Routes() {
  const { session } = useAuth();
  const { path, navigate } = useRouter();
  if (!session) return <Login />;
  const allowed = (to: string) => (NAV.find((n) => n.to === to)?.roles ?? []).includes(session.role as Role);
  const page = (to: string, el: ReactNode) => (allowed(to) ? el : <ErrorState error={new Error("Your role does not have access to this page.")} onRetry={() => navigate("/")} />);
  let content: ReactNode;
  const eq = match("/equipment/:id", path);
  const clean = path.split("?")[0];
  if (eq) content = <Equipment key={eq.id} id={+eq.id} />;
  else if (clean === "/alerts") content = <Alerts />;
  else if (clean === "/health") content = <Health />;
  else if (clean === "/reports") content = page("/reports", <Reports />);
  else if (clean === "/audit") content = page("/audit", <Audit />);
  else if (clean === "/admin") content = page("/admin", <Admin />);
  else content = <Overview />;
  return <Shell>{content}</Shell>;
}

export function App() {
  return (
    <ToastProvider>
      <AuthProvider allowRoleSwitch={config.useMocks}>
        <Router><Routes /></Router>
      </AuthProvider>
    </ToastProvider>
  );
}
