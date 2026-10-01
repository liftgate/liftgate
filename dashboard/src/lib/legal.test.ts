import assert from "node:assert/strict";
import { test } from "node:test";
import { legalLinks } from "./legal.ts";

const providers = { oauth: [], passkey: true, email: false, sso: true, customDomains: true, storage: true, deployDomain: "liftgate.app" };

test("legalLinks is empty until the operator sets the legal urls", () => {
  assert.deepEqual(legalLinks(undefined), []);
  assert.deepEqual(legalLinks(providers), []);
});

test("legalLinks keeps only the documents that are set, in footer order", () => {
  assert.deepEqual(legalLinks({ ...providers, aupUrl: "https://liftgate.dev/legal/aup", termsUrl: "https://liftgate.dev/legal/terms" }), [
    { label: "Terms", href: "https://liftgate.dev/legal/terms" },
    { label: "Acceptable Use", href: "https://liftgate.dev/legal/aup" },
  ]);
});
