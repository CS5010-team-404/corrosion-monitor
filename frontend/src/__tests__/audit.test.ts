import { afterEach, describe, expect, it, vi } from "vitest";
import { auditPage } from "../api/endpoints";

afterEach(() => vi.unstubAllGlobals());

describe("audit response shapes", () => {
  const entry = { id: 7, occurredAt: "2026-11-20T10:00:00Z", actorType: "USER", actor: "a@x", action: "USER_LOGIN", entityType: null, entityId: null };
  const respond = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

  it("reads the agreed paged shape and sends page, size and filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond({ items: [entry], page: 1, size: 10, total: 31 }));
    vi.stubGlobal("fetch", fetchMock);
    const r = await auditPage({ page: 1, size: 10, actor: "a@x", from: null, to: null });
    expect(r).toMatchObject({ paged: true, total: 31, page: 1, size: 10 });
    expect(r.items[0].id).toBe(7);
    expect(fetchMock.mock.calls[0][0]).toContain("/api/v1/audit?page=1&size=10&actor=a%40x");
  });

  it("still accepts the older plain array", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(respond([entry, { ...entry, id: 6 }])));
    const r = await auditPage({ page: 0, size: 200 });
    expect(r).toMatchObject({ paged: false, total: 2 });
    expect(r.items.map((e) => e.id)).toEqual([7, 6]);
  });
});
