import assert from "node:assert/strict";
import { test } from "node:test";
import { showsLanding } from "./landing.ts";

const jar = (...names: string[]) => (name: string) => names.includes(name);

test("showsLanding stays off unless the flag is true", () => {
  assert.equal(showsLanding(undefined, jar()), false);
  assert.equal(showsLanding("false", jar()), false);
  assert.equal(showsLanding("1", jar()), false);
});

test("showsLanding shows the landing to visitors without a session cookie", () => {
  assert.equal(showsLanding("true", jar()), true);
  assert.equal(showsLanding("true", jar("theme")), true);
});

test("showsLanding sends either session cookie to the dashboard", () => {
  assert.equal(showsLanding("true", jar("__Host-liftgate_session")), false);
  assert.equal(showsLanding("true", jar("liftgate_session")), false);
});
