import type { EnvVar } from "../src/lib/types";
import { asOperator, build, deployment, environment, expect, openNav, org, project, Reply, service, servicePath, settled, test, type Api } from "./fixtures";

const patch = `PATCH /services/${service.id}`;
const settings = `${servicePath}?tab=settings`;

const patched = (api: Api) => api.on(patch, (body: unknown) => ({ ...service, ...(body as object) }));

test("the build card saves only its fields and offers to rebuild the running commit", async ({ page, api }) => {
  patched(api);
  api.on(`POST /services/${service.id}/deploy`, new Reply(201, { ...build, id: "build-2", status: "queued" }));
  await page.goto(`${settings}&section=build`);
  await page.getByLabel("Build command").fill("pnpm build:web");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /^Saved\.$/ })).toBeVisible();
  expect(api.sent(patch).map((call) => call.body)).toEqual([{ rootDir: "/", buildCommand: "pnpm build:web", dockerfilePath: "Dockerfile", watchPaths: [], buildStrategy: "auto" }]);
  await expect(page.getByRole("tabpanel").getByRole("button", { name: "Redeploy", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Rebuild and deploy" }).click();
  await expect(page.getByText("Saved. Rebuilding 4f2a9c1.")).toBeVisible();
  expect(api.sent(`POST /services/${service.id}/deploy`).map((call) => call.body)).toEqual([{ ref: build.commitSha }]);
});

test("a new port applies with a redeploy and no rebuild", async ({ page, api }) => {
  patched(api);
  api.on(`POST /services/${service.id}/redeploy`, new Reply(201, deployment));
  await page.goto(`${settings}&section=runtime`);
  await page.getByRole("spinbutton", { name: /^Port/ }).fill("3000");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  const apply = page.getByRole("tabpanel").getByRole("button", { name: "Redeploy", exact: true });
  await expect(apply).toBeVisible();
  await expect(page.getByRole("button", { name: "Rebuild and deploy" })).toHaveCount(0);
  await apply.click();
  await expect(page.getByText("Saved. Redeploying without a rebuild.")).toBeVisible();
  expect(api.sent(patch).map((call) => call.body)).toEqual([{ startCommand: null, port: 3000, healthCheckPath: "/healthz" }]);
  expect(api.sent(`POST /services/${service.id}/redeploy`)).toHaveLength(1);
});

test("a service that never ran applies its settings on the first deploy", async ({ page, api }) => {
  api.on("GET /orgs/acme/projects/shop/tree", { project, environments: [environment], services: [{ ...service, current: null }] });
  patched(api);
  await page.goto(settings);
  await page.getByRole("textbox", { name: "Name" }).fill("Storefront");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved. Applies on the first deploy.")).toBeVisible();
  await expect(page.getByRole("button", { name: /^(Redeploy|Rebuild and deploy)$/ })).toHaveCount(0);
  expect(api.sent(patch).map((call) => call.body)).toEqual([{ name: "Storefront", kind: "web", cronSchedule: null }]);
});

test("running as a worker or cron job clears the runtime fields that kind cannot use", async ({ page, api }) => {
  patched(api);
  for (const [kind, schedule] of [["Worker"], ["Cron job", "*/5 * * * *"]]) {
    await page.goto(settings);
    await page.getByLabel("Runs as").selectOption(kind);
    if (schedule) await page.getByLabel("Cron schedule").fill(schedule);
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: /^Saved\.$/ })).toBeVisible();
  }
  expect(api.sent(patch).map((call) => call.body)).toEqual([
    { name: "Web", kind: "worker", cronSchedule: null, healthCheckPath: null },
    { name: "Web", kind: "cron", cronSchedule: "*/5 * * * *", port: null, healthCheckPath: null },
  ]);
});

test("a public build-time variable asks for a rebuild and a runtime secret for a redeploy", async ({ page, api }) => {
  api.on(`GET /services/${service.id}/env`, [
    { name: "NEXT_PUBLIC_X", value: "a", secret: false },
    { name: "API_TOKEN", value: null, secret: true },
  ]);
  api.on(`PUT /services/${service.id}/env`, (body: unknown) => (body as EnvVar[]).map((v) => (v.secret ? { ...v, value: null } : v)));
  await page.goto(`${servicePath}?tab=env`);
  const values = page.getByLabel("Value");
  await values.first().fill("b");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("button", { name: "Rebuild and deploy" })).toBeVisible();
  await values.nth(1).fill("new-token");
  await expect(page.getByRole("button", { name: "Rebuild and deploy" })).toHaveCount(0);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("tabpanel").getByRole("button", { name: "Redeploy", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Rebuild and deploy" })).toHaveCount(0);
});

test("no page offers save and redeploy, and the service header has one deploy control", async ({ page, api }) => {
  for (const path of [
    ...["general", "build", "runtime", "resources"].map((section) => `${settings}&section=${section}`),
    `${servicePath}?tab=env`,
    "/acme/shop/settings",
    "/acme/shop",
  ]) {
    await page.goto(path);
    await settled(page);
    await expect(page.getByRole("button", { name: /save and redeploy/i })).toHaveCount(0);
  }
  api.on(`GET /services/${service.id}/deployments`, []);
  api.on(`GET /services/${service.id}/builds`, []);
  await page.goto(servicePath);
  await expect(page.getByText("No deployments yet")).toBeVisible();
  const control = page.getByRole("group", { name: "Deploy" });
  await expect(control).toHaveCount(1);
  expect(await page.getByRole("button", { name: /deploy/i }).count()).toBe(await control.getByRole("button").count());
});

test("deploying a branch from the header opens its build in the deployments tab", async ({ page, api }) => {
  api.on(`POST /services/${service.id}/deploy`, new Reply(201, { ...build, id: "build-2", status: "queued" }));
  await page.goto(`${servicePath}?tab=metrics`);
  await page.getByRole("button", { name: "More deploy options" }).click();
  await page.getByRole("menuitem", { name: "Deploy a branch or commit…" }).click();
  await page.getByRole("dialog").getByLabel("Branch or commit").fill("release/1.2");
  await page.getByRole("dialog").getByRole("button", { name: "Deploy" }).click();
  await page.waitForURL(`${servicePath}?tab=deployments&build=build-2`);
  expect(api.sent(`POST /services/${service.id}/deploy`).map((call) => call.body)).toEqual([{ ref: "release/1.2" }]);
});

test("the old builds link opens the deployments tab with that build's log, next to builds that never deployed", async ({ page, api }) => {
  const failed = { ...build, id: "build-2", commitSha: "9c1e7b3d5a6f8e9c0b1a2d3e4f5a6b7c8d9e4f2a", status: "failed", error: "could not determine how to build the app", createdAt: new Date().toISOString() };
  api.on(`GET /services/${service.id}/builds`, [failed, build]);
  await page.goto(`${servicePath}?tab=builds&build=${build.id}`);
  await expect(page.getByRole("tab", { name: "Deployments" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("region", { name: "Build 4f2a9c1 output" })).toContainText("Listening on port 8080");
  await expect(page.getByRole("row", { name: /9c1e7b3/ })).toContainText("could not determine how to build the app");
  await expect(page.getByRole("region", { name: /Build 9c1e7b3/ })).toHaveCount(0);
});

test("a live deployment with finished builds stops polling and leaves an older failed build closed", async ({ page, api }) => {
  const failed = { ...build, id: "build-0", commitSha: "9c1e7b3d5a6f8e9c0b1a2d3e4f5a6b7c8d9e4f2a", status: "failed", createdAt: new Date(Date.now() - 86_400_000).toISOString() };
  api.on(`GET /services/${service.id}/builds`, [build, failed]);
  await page.clock.install();
  await page.goto(servicePath);
  await settled(page);
  await expect(page.getByRole("row", { name: /9c1e7b3/ })).toBeVisible();
  await expect(page.getByRole("region", { name: /^Build .* output$/ })).toHaveCount(0);
  const polled = api.sent(`GET /services/${service.id}/deployments`).length;
  await page.clock.runFor(30_000);
  await page.waitForTimeout(500);
  expect(api.sent(`GET /services/${service.id}/deployments`)).toHaveLength(polled);
});

test("only owners see delete organization, and it waits for the slug", async ({ page, api }) => {
  api.on("DELETE /orgs/acme", () => {
    api.on("GET /orgs", []);
  });
  await page.goto("/acme/settings");
  await page.getByRole("button", { name: "Delete organization" }).click();
  const dialog = page.getByRole("dialog");
  const confirm = dialog.getByRole("button", { name: "Delete organization" });
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel("Type acme to confirm").fill("acme");
  await confirm.click();
  await page.waitForURL("/dashboard");
  expect(api.sent("DELETE /orgs/acme")).toHaveLength(1);

  api.on("GET /orgs", [org]);
  api.on("GET /orgs/acme", { ...org, role: "admin" });
  await page.goto("/acme/settings");
  await settled(page);
  await expect(page.getByRole("heading", { name: "Plan and usage" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete organization" })).toHaveCount(0);
});

const raw = ["web", "worker", "cron", "static", "production", "preview", "owner", "admin", "member", "auto", "dockerfile", "pending", "active", "suspended", "free", "unlimited", "default"];

test("no select shows a raw enum value", async ({ page, api }) => {
  await asOperator(api);
  const pages: [string, string?][] = [
    [settings],
    ["/acme/settings/members", "Invite"],
    ["/acme/settings/sso"],
    ["/acme/settings/notifications", "New channel"],
    ["/acme/shop/settings", "New environment"],
    ["/dashboard/operator?view=users"],
    ["/dashboard/operator?view=orgs"],
    ["/new?org=acme&repo=acme/shop", "Edit"],
  ];
  for (const [path, open] of pages) {
    await page.goto(path);
    await settled(page);
    if (open) await page.getByRole("button", { name: open, exact: true }).first().click();
    const options = await page.locator("select option").allTextContents();
    expect(options.length, path).toBeGreaterThan(0);
    expect(options.filter((text) => raw.includes(text.trim())), path).toEqual([]);
  }
});

const empty: [string, string, (api: Api) => void][] = [
  ["projects", "/acme", (api) => api.on("GET /orgs/acme/projects", [])],
  ["tokens", "/acme/settings/tokens", () => {}],
  ["notification channels", "/acme/settings/notifications", () => {}],
  ["variables", `${servicePath}?tab=env`, (api) => api.on(`GET /services/${service.id}/env`, [])],
  ["services", "/acme/shop", (api) => api.on("GET /orgs/acme/projects/shop/tree", { project, environments: [environment], services: [] })],
];

for (const [name, path, setup] of empty) {
  test(`without ${name} each action is offered once`, async ({ page, api }) => {
    setup(api);
    await page.goto(path);
    await settled(page);
    const controls: string[] = (await page.locator("main").ariaSnapshot()).match(/- (button|link) "[^"]*"/g) ?? [];
    expect(controls.length).toBeGreaterThan(0);
    expect(controls.filter((control, i) => controls.indexOf(control) !== i)).toEqual([]);
  });
}

test("the organization switcher leads to the organization's general settings with its plan", async ({ page }) => {
  await page.goto("/acme/shop");
  await settled(page);
  await (await openNav(page)).getByRole("button", { name: /Organization: Acme/ }).click();
  await page.getByRole("menuitem", { name: "Organization settings" }).click();
  await page.waitForURL("/acme/settings");
  await expect(page.getByRole("heading", { name: "General" })).toBeVisible();
  await expect(page.getByText("Free plan", { exact: true })).toBeVisible();
});
