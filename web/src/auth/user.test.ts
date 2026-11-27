import { describe, expect, it } from "vitest";
import { fakeBackend, json, noContent } from "../test/fakeBackend";
import { alice } from "../test/renderApp";
import { sessionEnd } from "./session";
import { fetchCurrentUser } from "./user";

describe("fetchCurrentUser", () => {
  it("is the user when someone is logged in", async () => {
    fakeBackend({ "GET /api/auth/me": () => json(200, alice) });
    expect(await fetchCurrentUser()).toEqual(alice);
  });

  it("is null when the backend says nobody is logged in (204), without ending a session", async () => {
    fakeBackend({ "GET /api/auth/me": () => noContent() });
    expect(await fetchCurrentUser()).toBeNull();
    // A visitor who was never logged in must not be told their session ended.
    expect(sessionEnd.get()).toBeNull();
  });
});
