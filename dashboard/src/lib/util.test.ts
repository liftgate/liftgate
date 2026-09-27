import assert from "node:assert/strict";
import { test } from "node:test";
import type { Environment, Project, Service } from "./types.ts";
import { envPayload, findService, keepsStoredValue, safeNext, shortSha, slugify, timeAgo } from "./util.ts";

test("slugify lowercases and collapses separators", () => {
  assert.equal(slugify("  Acme Web App!  "), "acme-web-app");
  assert.equal(slugify("already-a-slug"), "already-a-slug");
  assert.equal(slugify("___"), "");
});

test("timeAgo buckets by age", () => {
  const at = (seconds: number) => new Date(Date.now() - seconds * 1000).toISOString();
  assert.equal(timeAgo(at(5)), "just now");
  assert.equal(timeAgo(at(120)), "2m ago");
  assert.equal(timeAgo(at(7200)), "2h ago");
  assert.equal(timeAgo(at(86400 * 3)), "3d ago");
  assert.match(timeAgo(at(86400 * 30)), /\d/);
});

test("shortSha keeps seven characters", () => {
  assert.equal(shortSha("0123456789abcdef"), "0123456");
});

test("safeNext keeps only same-origin relative paths", () => {
  assert.equal(safeNext("/acme/web?tab=logs"), "/acme/web?tab=logs");
  for (const value of ["//evil.dev", "/\\evil.dev", "/\t/evil.dev", "/\n/evil.dev", "https://evil.dev", "acme", undefined, ["/a"]]) assert.equal(safeNext(value), "/");
});

test("envPayload never sends an empty value for an untouched stored secret", () => {
  const stored = { name: "TOKEN", value: null, secret: true };
  const typed = { name: "KEY", value: "s3cret", secret: true };
  const plain = { name: "MODE", value: "", secret: false };
  assert.deepEqual(envPayload([stored, typed, plain, { name: "EMPTY", value: "", secret: true }]), [stored, typed, plain, { name: "EMPTY", value: null, secret: true }]);
  assert.throws(() => envPayload([{ ...stored, secret: false }]), /Retype the value of TOKEN/);
});

test("a stored secret stays locked until a new value is typed, even after typing and clearing", () => {
  const cleared = { name: "TOKEN", value: "", secret: true };
  for (const row of [{ ...cleared, value: null }, cleared]) assert.equal(keepsStoredValue(row), true);
  assert.equal(keepsStoredValue({ ...cleared, value: "a" }), false);
  assert.deepEqual(envPayload([cleared]), [{ ...cleared, value: null }]);
});

test("findService resolves the service in the environment named by the url", () => {
  const environments = [{ id: "p", slug: "production" }, { id: "s", slug: "staging" }] as Environment[];
  const services = [{ id: "production-worker", environmentId: "p", slug: "worker" }, { id: "staging-worker", environmentId: "s", slug: "worker" }] as Service[];
  const tree = { project: {} as Project, environments, services };
  assert.equal(findService(tree, "staging", "worker")?.service.id, "staging-worker");
  assert.equal(findService(tree, "production", "worker")?.environment.slug, "production");
  assert.equal(findService(tree, "preview", "worker"), undefined);
});
