import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const fields = Object.fromEntries(
  readFileSync(new URL("../../public/.well-known/security.txt", import.meta.url), "utf8")
    .trimEnd()
    .split("\n")
    .map((line) => line.split(/: (.*)/, 2)),
);

test("security.txt points reporters at the advisory form", () => {
  assert.equal(fields.Contact, "https://github.com/liftgate/liftgate/security/advisories/new");
  assert.equal(fields.Canonical, "https://liftgate.dev/.well-known/security.txt");
});

test("security.txt expires within the coming year", () => {
  const left = Date.parse(fields.Expires) - Date.now();
  assert.ok(left > 0 && left < 366 * 86_400_000, `Expires ${fields.Expires} is not within the coming year`);
});
