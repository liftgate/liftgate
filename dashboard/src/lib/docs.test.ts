import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { test } from "node:test";
import { docsPages, docsUrl } from "../components/landing/links.ts";

test("documentation links point at the release tag, or at main for builds without a release version", () => {
  const page = "https://github.com/liftgate/liftgate/blob/%s/documentation/runtime-contract.md";
  assert.equal(docsUrl("runtime-contract", "0.2.0"), page.replace("%s", "v0.2.0"));
  assert.equal(docsUrl("runtime-contract", "0.2.0-alpha.4"), page.replace("%s", "v0.2.0-alpha.4"));
  for (const version of [undefined, "", "pr-47", "latest"]) assert.equal(docsUrl("runtime-contract", version), page.replace("%s", "main"), String(version));
});

test("every linked documentation page exists", () => {
  for (const page of docsPages) assert.ok(existsSync(new URL(`../../../documentation/${page}.md`, import.meta.url)), page);
});
