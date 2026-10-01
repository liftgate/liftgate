import assert from "node:assert/strict";
import { test } from "node:test";
import type { Build, Deployment, Environment, Project, Service } from "./types.ts";
import {
  canRollBack,
  currentDeployment,
  duration,
  envPayload,
  findService,
  keepsStoredValue,
  linkTarget,
  pageUrl,
  platformHost,
  safeNext,
  shortSha,
  slugify,
  storedRows,
  stripAnsi,
  timeAgo,
} from "./util.ts";

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

test("duration counts from the start to the finish, or to now while running", () => {
  const start = "2026-09-28T10:00:00Z";
  assert.equal(duration(null, null), undefined);
  assert.equal(duration(start, "2026-09-28T10:00:42Z"), "42s");
  assert.equal(duration(start, "2026-09-28T10:02:05Z"), "2m 5s");
  assert.equal(duration(start, "2026-09-28T11:03:00Z"), "1h 3m");
  assert.equal(duration(new Date(Date.now() - 90_000).toISOString(), null), "1m 30s");
});

test("stripAnsi leaves no escape sequence in a log line", () => {
  const esc = String.fromCharCode(27);
  const line = `${esc}[1;32m✔ built${esc}[0m ${esc}]8;;https://liftgate.dev${esc}\\docs${esc}]8;;${esc}\\ ${esc}[2K${esc}(Bdone${esc}7`;
  assert.equal(stripAnsi(line), "✔ built docs done");
  assert.equal(stripAnsi("plain [1m text"), "plain [1m text");
  assert.ok(!stripAnsi(`${esc}[38;5;196mred${esc}[m`).includes(esc));
});

test("safeNext keeps only same-origin relative paths and falls back to the dashboard", () => {
  assert.equal(safeNext("/acme/web?tab=logs"), "/acme/web?tab=logs");
  for (const value of ["//evil.dev", "/\\evil.dev", "/\t/evil.dev", "/\n/evil.dev", "https://evil.dev", "acme", undefined, ["/a"]]) assert.equal(safeNext(value), "/dashboard");
});

test("linkTarget opens a new tab only for links that leave the site", () => {
  const newTab = { target: "_blank", rel: "noreferrer" };
  for (const href of ["/dashboard", "/login", "/.well-known/security.txt", "https://liftgate.dev/legal/terms"]) assert.deepEqual(linkTarget(href, "https://liftgate.dev"), {});
  for (const href of ["https://github.com/liftgate/liftgate", "https://railpack.com", "https://web-hello-dean.liftgate.app", "https://liftgate.dev.evil.dev/terms", "https://", "https://liftgate dev/terms", "https://liftgate.dev:99999/"])
    assert.deepEqual(linkTarget(href, "https://liftgate.dev"), newTab);
  assert.deepEqual(linkTarget("/legal/terms"), {});
  assert.deepEqual(linkTarget("https://github.com/liftgate/liftgate"), newTab);
});

test("envPayload never sends an empty value for an untouched stored secret", () => {
  const token = { name: "TOKEN", value: null, secret: true };
  const [stored] = storedRows([token]);
  const typed = { name: "KEY", value: "s3cret", secret: true };
  const plain = { name: "MODE", value: "", secret: false };
  assert.deepEqual(envPayload([stored, typed, plain, { ...stored, name: "EMPTY", value: "" }]), [token, typed, plain, { name: "EMPTY", value: null, secret: true }]);
  assert.throws(() => envPayload([{ ...stored, secret: false }]), /Retype the value of TOKEN/);
});

test("a stored secret stays locked until a new value is typed, even after typing and clearing", () => {
  const [stored] = storedRows([{ name: "TOKEN", value: null, secret: true }]);
  const cleared = { ...stored, value: "" };
  for (const row of [stored, cleared]) assert.equal(keepsStoredValue(row), true);
  assert.equal(keepsStoredValue({ ...cleared, value: "a" }), false);
  assert.deepEqual(envPayload([cleared]), [{ name: "TOKEN", value: null, secret: true }]);
});

test("a new secret row stays editable until it is saved", () => {
  const added = { name: "KEY", value: "", secret: true };
  const [plain] = storedRows([{ name: "MODE", value: "on", secret: false }]);
  for (const row of [added, { ...plain, value: "", secret: true }]) assert.equal(keepsStoredValue(row), false);
  assert.deepEqual(envPayload([added]), [added]);
  const [saved] = storedRows(envPayload([{ ...added, value: "s3cret" }]));
  assert.equal(keepsStoredValue(saved), true);
});

test("findService resolves the service in the environment named by the url", () => {
  const environments = [{ id: "p", slug: "production" }, { id: "s", slug: "staging" }] as Environment[];
  const services = [{ id: "production-worker", environmentId: "p", slug: "worker" }, { id: "staging-worker", environmentId: "s", slug: "worker" }] as Service[];
  const tree = { project: {} as Project, environments, services };
  assert.equal(findService(tree, "staging", "worker")?.service.id, "staging-worker");
  assert.equal(findService(tree, "production", "worker")?.environment.slug, "production");
  assert.equal(findService(tree, "preview", "worker"), undefined);
});

test("platformHost previews the readable hostname the API assigns, and gives up past one DNS label", () => {
  const labels = { service: "api", environment: "production", project: "shop", org: "acme" };
  assert.equal(platformHost(labels, "liftgate.app"), "api-shop-acme.liftgate.app");
  assert.equal(platformHost({ ...labels, environment: "staging" }, "apps.example.net"), "api-staging-shop-acme.apps.example.net");
  assert.equal(platformHost({ ...labels, service: "a".repeat(40), project: "b".repeat(20) }, "liftgate.app"), undefined);
  assert.equal(platformHost({ ...labels, service: "shop" }, "liftgate.app"), "shop-acme.liftgate.app");
  assert.equal(platformHost({ ...labels, service: "shop", environment: "staging" }, "liftgate.app"), "staging-shop-acme.liftgate.app");
});

test("the current deployment is the running one, else the newest", () => {
  const rows = (...statuses: string[]) => statuses.map((status, i) => ({ id: `d${i}`, status }));
  assert.equal(currentDeployment(rows("failed", "running", "superseded"))?.id, "d1");
  assert.equal(currentDeployment(rows("pending", "failed"))?.id, "d0");
  assert.equal(currentDeployment([]), undefined);
});

test("rollback is offered only to replaced deployments whose image is still retained", () => {
  const build = { imagePruned: false } as Build;
  const at = (status: Deployment["status"]) => ({ status }) as Deployment;
  assert.equal(canRollBack(at("superseded"), build), true);
  assert.equal(canRollBack(at("rolled_back"), build), true);
  for (const status of ["running", "failed", "pending", "releasing"] as const) assert.equal(canRollBack(at(status), build), false, status);
  assert.equal(canRollBack(at("superseded"), { imagePruned: true } as Build), false);
  assert.equal(canRollBack(at("superseded")), false);
});

test("pageUrl adds the page size and the cursor to any path", () => {
  assert.equal(pageUrl("/orgs/acme/audit", 50), "/orgs/acme/audit?limit=50");
  assert.equal(pageUrl("/orgs/acme/audit", 50, 0), "/orgs/acme/audit?limit=50&before=0");
  assert.equal(pageUrl("/operator/users?status=pending", 50, "user-1"), "/operator/users?status=pending&limit=50&before=user-1");
});
