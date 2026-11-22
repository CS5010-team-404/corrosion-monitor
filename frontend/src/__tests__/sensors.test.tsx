import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setSensorInterval } from "../api/endpoints";
import type { Device } from "../api/types";
import { intervalLabel, intervalProblem } from "../lib/sensors";
import { IntervalDialog } from "../pages/Admin";

const device: Device = { id: 3, deviceId: "SIM-003", kind: "SIMULATED", model: "SIM-SHT31", expectedIntervalS: 15, active: true, equipmentId: 3, equipmentName: "Rack A3", locationName: "Server Room A" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("reporting interval rules", () => {
  it("accepts whole seconds from 5 to 3600, like the platform", () => {
    expect(intervalProblem("5")).toBeNull();
    expect(intervalProblem(" 3600 ")).toBeNull();
    expect(intervalProblem("4")).toBe("Between 5 and 3600 seconds.");
    expect(intervalProblem("3601")).toBe("Between 5 and 3600 seconds.");
    expect(intervalProblem("")).toBe("Enter a number of seconds.");
    expect(intervalProblem("12.5")).toBe("Use whole seconds.");
    expect(intervalProblem("-10")).toBe("Use whole seconds.");
  });
  it("labels intervals for people", () => {
    expect(intervalLabel(15)).toBe("15 s");
    expect(intervalLabel(60)).toBe("1 min");
    expect(intervalLabel(90)).toBe("1 min 30 s");
    expect(intervalLabel(3600)).toBe("1 h");
  });
});

describe("setSensorInterval", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => { fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); });
  afterEach(() => vi.unstubAllGlobals());

  it("PATCHes the platform's admin sensor path with the new interval", async () => {
    fetchMock.mockResolvedValue(json({ ...device, expectedIntervalS: 30 }));
    const d = await setSensorInterval(3, 30);
    expect(d.expectedIntervalS).toBe(30);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toMatch(/\/api\/v1\/admin\/sensors\/3$/);
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body)).toEqual({ expectedIntervalS: 30 });
  });

  it("surfaces the server's message on a rejected value", async () => {
    fetchMock.mockResolvedValue(json({ message: "expectedIntervalS must be between 5 and 3600 seconds" }, 400));
    await expect(setSensorInterval(3, 1)).rejects.toThrow("expectedIntervalS must be between 5 and 3600 seconds");
  });
});

describe("IntervalDialog", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => { fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); });
  afterEach(() => vi.unstubAllGlobals());

  const save = () => screen.getByRole("button", { name: "Save interval" }) as HTMLButtonElement;

  it("blocks invalid and unchanged values without calling the server", () => {
    render(<IntervalDialog device={device} onClose={() => {}} onSaved={() => {}} />);
    expect(save().disabled).toBe(true); // unchanged
    fireEvent.change(screen.getByLabelText(/Interval in seconds/), { target: { value: "4" } });
    expect(screen.getByRole("alert").textContent).toBe("Between 5 and 3600 seconds.");
    expect(save().disabled).toBe(true);
    fireEvent.click(save());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("saves a valid value and hands back the updated device", async () => {
    fetchMock.mockResolvedValue(json({ ...device, expectedIntervalS: 60 }));
    const onSaved = vi.fn();
    render(<IntervalDialog device={device} onClose={() => {}} onSaved={onSaved} />);
    fireEvent.change(screen.getByLabelText(/Interval in seconds/), { target: { value: "60" } });
    expect(screen.queryByRole("alert")).toBeNull();
    await act(async () => { fireEvent.click(save()); });
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ expectedIntervalS: 60 }));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ expectedIntervalS: 60 });
  });
});

describe("mock backend", () => {
  afterEach(() => sessionStorage.clear());
  const call = async (role: string, expectedIntervalS: unknown) => {
    sessionStorage.setItem("cw.session", JSON.stringify({ role }));
    const { mockFallback } = await import("../mock/server");
    return mockFallback("/api/v1/admin/sensors/2", { method: "PATCH", body: JSON.stringify({ expectedIntervalS }) });
  };

  it("follows the platform's rules for the interval", async () => {
    expect(await call("ADMIN", 45)).toMatchObject({ id: 2, expectedIntervalS: 45 });
    await expect(call("ADMIN", 4)).rejects.toMatchObject({ status: 400 });
    await expect(call("TECHNICIAN", 30)).rejects.toMatchObject({ status: 403 });
  });
});
