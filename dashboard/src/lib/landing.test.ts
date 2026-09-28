import assert from "node:assert/strict";
import { test } from "node:test";
import { landingFor } from "./landing.ts";

const jar = (...names: string[]) => (name: string) => names.includes(name);

test("landingFor sends everyone to the dashboard unless the flag is true", () => {
  for (const flag of [undefined, "false", "1"]) {
    assert.equal(landingFor(flag, jar()), undefined);
    assert.equal(landingFor(flag, jar("__Host-liftgate_session")), undefined);
  }
});

test("landingFor shows the landing signed out to visitors without a session cookie", () => {
  assert.deepEqual(landingFor("true", jar()), { signedIn: false });
  assert.deepEqual(landingFor("true", jar("theme")), { signedIn: false });
});

test("landingFor shows the landing signed in with either session cookie", () => {
  assert.deepEqual(landingFor("true", jar("__Host-liftgate_session")), { signedIn: true });
  assert.deepEqual(landingFor("true", jar("liftgate_session")), { signedIn: true });
});
