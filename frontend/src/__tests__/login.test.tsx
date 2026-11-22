import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { config } from "../lib/config";
import { installMocks } from "../mock/server";

/**
 * Sign-in as a user sees it: the error for a bad email or password, the lockout message after five failures, and a
 * successful sign-in. The first group uses the mock backend, whose lockout counter is one for the whole demo and
 * is reset by a successful sign-in, so the success test runs before the lockout test. The second group stubs fetch
 * for the network-failure and server lockout messages. (Its own file: the mock's counter is module state.)
 */
const WAIT = { timeout: 4000 };

function signIn(email: string, password: string) {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

beforeEach(() => {
  sessionStorage.clear();
  history.replaceState(null, "", "/");
});

describe("with the mock backend", () => {
  beforeAll(() => {
    window.scrollTo = () => {};
    config.useMocks = true;
    installMocks(() => {});
  });

  it("an unknown email gets the same message as a wrong password", async () => {
    render(<App />);
    signIn("nobody@example.com", "secret");
    expect((await screen.findByRole("alert", {}, WAIT)).textContent).toContain("Incorrect email or password.");
    expect(screen.queryByRole("button", { name: "Notifications" })).toBeNull();   // still signed out
  });

  it("a correct sign-in opens the dashboard for that role", async () => {
    render(<App />);
    signIn("manager@corrosionwatch.app", "anything");
    expect(await screen.findByRole("button", { name: "Notifications" }, WAIT)).toBeTruthy();
    expect(JSON.parse(sessionStorage.getItem("cw.session")!)).toMatchObject({ role: "MANAGER", fullName: "Morgan Manager" });
  });

  it("five wrong passwords lock the account, with the time it unlocks", async () => {
    render(<App />);
    for (let i = 1; i <= 4; i++) {
      signIn("tech@corrosionwatch.app", "wrong");
      expect((await screen.findByRole("alert", {}, WAIT)).textContent).toContain("Incorrect email or password.");
      await screen.findByRole("button", { name: "Sign in" }, WAIT);   // not busy any more
    }
    signIn("tech@corrosionwatch.app", "wrong");
    const msg = await screen.findByText(/Account locked after 5 failed sign-ins\. Try again after .+ \(UTC\)\./, {}, WAIT);
    expect(msg).toBeTruthy();
    await screen.findByRole("button", { name: "Sign in" }, WAIT);
    signIn("tech@corrosionwatch.app", "the-right-one");                // refused while locked
    expect(await screen.findByText(/Account locked/, {}, WAIT)).toBeTruthy();
  });
});

describe("with the real API", () => {
  beforeAll(() => { config.useMocks = false; });
  afterEach(() => vi.unstubAllGlobals());

  it("explains a network failure in plain words", async () => {
    const { setMockHandler } = await import("../lib/api");
    setMockHandler(null as never);   // the earlier group installed the mock handler; talk to "the network" instead
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    render(<App />);
    signIn("tech@corrosionwatch.app", "secret");
    expect((await screen.findByRole("alert", {}, WAIT)).textContent).toContain("Can't reach the server.");
  });

  it("shows the server's lockout message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 423, message: "Account locked after 5 failed sign-ins. Try again after 2026-11-21T10:15:00Z (UTC)." }),
      { status: 423, headers: { "Content-Type": "application/json" } })));
    render(<App />);
    signIn("tech@corrosionwatch.app", "secret");
    expect((await screen.findByRole("alert", {}, WAIT)).textContent).toContain("Try again after 2026-11-21T10:15:00Z (UTC).");
  });
});
