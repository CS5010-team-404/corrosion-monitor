import { useEffect, useRef } from "react";
import { tokenStore } from "./api";
import { config } from "./config";
import { StompClient } from "./stomp";

// Topics: /topic/readings and /topic/health come from the data platform; /topic/risk and /topic/alerts from the
// team backend. In mock mode nothing connects: pages poll instead.
const PLATFORM_TOPICS = ["/topic/readings", "/topic/health"];
const clients: Record<string, StompClient> = {};

function client(url: string) {
  if (!clients[url]) {
    clients[url] = new StompClient(url, tokenStore.get);
    clients[url].start();
  }
  return clients[url];
}

/** Calls onMessage for every message on topic while mounted. */
export function useLive<T>(topic: string, onMessage: (body: T) => void) {
  const ref = useRef(onMessage);
  ref.current = onMessage;
  useEffect(() => {
    if (config.useMocks) return;
    const url = PLATFORM_TOPICS.includes(topic) ? config.wsPlatform : config.wsApi;
    return client(url).subscribe(topic, (b) => ref.current(b as T));
  }, [topic]);
}
