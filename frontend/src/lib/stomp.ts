// A ~70-line STOMP 1.2 client over WebSocket, enough for Spring's simple broker: CONNECT, SUBSCRIBE, MESSAGE,
// automatic reconnect. Avoids a dependency for something this small.
type Listener = (body: unknown) => void;

export class StompClient {
  private ws: WebSocket | null = null;
  private subs = new Map<string, Set<Listener>>();
  private connected = false;
  private nextId = 0;
  private stopped = false;

  constructor(private url: string, private token?: () => string | null) {}

  start() {
    this.stopped = false;
    this.open();
  }

  stop() {
    this.stopped = true;
    this.ws?.close();
  }

  subscribe(topic: string, fn: Listener): () => void {
    const first = !this.subs.has(topic);
    if (first) this.subs.set(topic, new Set());
    this.subs.get(topic)!.add(fn);
    if (first && this.connected) this.sendSubscribe(topic);
    return () => this.subs.get(topic)?.delete(fn);
  }

  private open() {
    try {
      this.ws = new WebSocket(this.url);
    } catch {
      this.retry();
      return;
    }
    this.ws.onopen = () => {
      const auth = this.token?.();
      this.frame("CONNECT", { "accept-version": "1.2", "heart-beat": "0,0", ...(auth ? { Authorization: `Bearer ${auth}` } : {}) });
    };
    this.ws.onmessage = (ev) => this.onData(String(ev.data));
    this.ws.onclose = () => {
      this.connected = false;
      this.retry();
    };
    this.ws.onerror = () => this.ws?.close();
  }

  private retry() {
    if (!this.stopped) setTimeout(() => this.open(), 3000);
  }

  private onData(data: string) {
    for (const raw of data.split("\0")) {
      const text = raw.replace(/^\n+/, "");
      if (!text) continue;
      const [head, ...rest] = text.split("\n\n");
      const lines = head.split("\n");
      const command = lines[0];
      const headers = Object.fromEntries(lines.slice(1).map((l) => [l.slice(0, l.indexOf(":")), l.slice(l.indexOf(":") + 1)]));
      if (command === "CONNECTED") {
        this.connected = true;
        for (const topic of this.subs.keys()) this.sendSubscribe(topic);
      } else if (command === "MESSAGE") {
        let body: unknown = rest.join("\n\n");
        try {
          body = JSON.parse(body as string);
        } catch {
          /* plain text */
        }
        this.subs.get(headers.destination)?.forEach((fn) => fn(body));
      }
    }
  }

  private sendSubscribe(topic: string) {
    this.frame("SUBSCRIBE", { id: `sub-${this.nextId++}`, destination: topic });
  }

  private frame(command: string, headers: Record<string, string>, body = "") {
    const head = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join("\n");
    this.ws?.send(`${command}\n${head}\n\n${body}\0`);
  }
}
