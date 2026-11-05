import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { Icon } from "../components/Icon";

interface Toast { id: number; text: string; warn?: boolean }
const ToastContext = createContext<(text: string, warn?: boolean) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((text: string, warn?: boolean) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t.slice(-3), { id, text, warn }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200);
  }, []);
  useEffect(() => {
    const on = (e: Event) => { const d = (e as CustomEvent).detail; push(d.text, d.warn); };
    window.addEventListener("cw:toast", on);
    return () => window.removeEventListener("cw:toast", on);
  }, [push]);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.warn ? "warn" : ""}`}><Icon name={t.warn ? "alert" : "check"} /><span>{t.text}</span></div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
export const useToast = () => useContext(ToastContext);

/** Toast from outside React (for example the mock backend's live alerts). */
export const toastEvent = (text: string, warn?: boolean) => window.dispatchEvent(new CustomEvent("cw:toast", { detail: { text, warn } }));
