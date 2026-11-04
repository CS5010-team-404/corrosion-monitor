import { describe, expect, it } from "vitest";
import { fmt, label, marginTone } from "../lib/format";
import { match } from "../lib/router";
import { isPlatformPath } from "../lib/config";

describe("format", () => {
  it("formats numbers and labels", () => {
    expect(fmt(3.14159)).toBe("3.1");
    expect(fmt(null)).toBe("–");
    expect(label("SIGNATURE_FAILURES")).toBe("Signature failures");
  });
  it("maps margins to status tones using the profile thresholds", () => {
    expect(marginTone(0.8, 3, 1)).toBe("crit");
    expect(marginTone(2.5, 3, 1)).toBe("warn");
    expect(marginTone(6, 3, 1)).toBe("ok");
  });
});

describe("router", () => {
  it("matches parameters and ignores the query string", () => {
    expect(match("/equipment/:id", "/equipment/4?x=1")).toEqual({ id: "4" });
    expect(match("/equipment/:id", "/alerts")).toBeNull();
  });
});

describe("api routing", () => {
  it("sends platform paths to the data platform", () => {
    expect(isPlatformPath("/api/v1/health/sensors")).toBe(true);
    expect(isPlatformPath("/api/v1/admin/simulator/SIM-001/scenario")).toBe(true);
    expect(isPlatformPath("/api/v1/alerts")).toBe(false);
  });
});
