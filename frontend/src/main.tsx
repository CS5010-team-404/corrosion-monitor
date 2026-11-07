import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { config } from "./lib/config";
import { toastEvent } from "./lib/toast";
import "./styles/design-system.css";

async function start() {
  if (config.useMocks) {
    // Loaded only in mock mode, so the simulation never ships in a production bundle.
    const { installMocks } = await import("./mock/server");
    installMocks((title) => toastEvent(title, true));
  }
  createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
}
start();
