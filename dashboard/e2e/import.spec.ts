import type { Organization, ServiceSpec } from "../src/lib/types";
import { build, detection, environment, expect, monorepo, org, project, projectDetection, Reply, service, servicePath, test, usage, type Api } from "./fixtures";

const created = { ...service, slug: "shop", name: "shop", current: null };
const posts = `POST /environments/${environment.id}/services`;

const deployable = (api: Api) => {
  api.on("POST /orgs/acme/projects", new Reply(201, project));
  api.on(`GET /projects/${project.id}/environments`, [environment]);
  api.on(posts, new Reply(201, { ...created, buildId: "build-2" }));
  api.on("GET /orgs/acme/projects/shop/tree", { project, environments: [environment], services: [created] });
  api.on(`GET /services/${service.id}/builds`, [{ ...build, id: "build-2", status: "running", finishedAt: null }]);
};

test("a new user goes from sign-in to a streaming build in four clicks without typing", async ({ page, api }) => {
  let orgs: Organization[] = [];
  api.on("GET /orgs", () => orgs);
  api.on("POST /orgs", () => {
    orgs = [org];
    return new Reply(201, org);
  });
  deployable(api);
  await page.route("**/api/v1/auth/github/login**", (route) => route.fulfill({ status: 302, headers: { location: "/dashboard" } }));

  await page.goto("/login");
  await page.getByRole("link", { name: "Continue with GitHub" }).click();
  await expect(page.getByLabel("Name")).toHaveValue("Ada Lovelace");
  await expect(page.getByText("Addresses end in -ada-lovelace.apps.example.com")).toBeVisible();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.waitForURL("/new?org=acme");
  await page.getByRole("link", { name: "Import acme/shop" }).click();
  await page.waitForURL("/new?org=acme&repo=acme/shop");

  await expect(page.getByRole("button", { name: "Deploy", exact: true })).toBeEnabled();
  await expect(page.getByText("From package.json (next) and pnpm-lock.yaml")).toBeVisible();
  await expect(page.getByText("https://shop-acme.apps.example.com").first()).toBeVisible();
  const variables = page.locator("section", { has: page.getByRole("heading", { name: "6 variables from .env.example, app.json" }) });
  await expect(variables.getByText("0 of 6 filled. Empty ones are skipped.", { exact: false })).toBeVisible();
  expect((await page.getByRole("textbox").count()) - (await variables.getByRole("textbox").count())).toBe(1);
  await page.getByRole("button", { name: "Deploy", exact: true }).click();

  await page.waitForURL("/acme/shop/production/shop?tab=deployments&build=build-2");
  await expect(page.getByRole("region", { name: /Build 4f2a9c1 output/ })).toContainText("Listening on port 8080");
  expect(api.sent("POST /orgs")[0].body).toEqual({ slug: "ada-lovelace", name: "Ada Lovelace" });
  expect(api.sent("POST /orgs/acme/projects")[0].body).toEqual({ slug: "shop", name: "shop", repoFullName: "acme/shop" });
  const [post] = api.sent(posts);
  expect(post.search).toBe("?deploy=true");
  expect(post.body).toMatchObject({ slug: "shop", name: "shop", kind: "web", rootDir: "/", framework: "next", cpuMillis: 500, memoryMb: 512 });
  expect(post.body).not.toHaveProperty("env");
});

test("a single root app takes the project's URL name, so the address shown is the one it gets", async ({ page, api }) => {
  deployable(api);
  api.on("POST /orgs/acme/projects", new Reply(201, { ...project, slug: "shop-2" }));
  api.on(posts, new Reply(409, { error: "plan_limit", message: "the free plan's services limit is 5" }));
  await page.goto("/new?org=acme&repo=acme/shop");
  await page.getByRole("button", { name: "Edit URL" }).click();
  await page.getByRole("textbox", { name: "URL name" }).fill("shop-2");
  await expect(page.getByText("https://shop-2-acme.apps.example.com").first()).toBeVisible();
  await page.getByRole("button", { name: "Deploy", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "the free plan's services limit is 5" })).toBeVisible();
  expect(api.sent("POST /orgs/acme/projects")[0].body).toEqual({ slug: "shop-2", name: "shop", repoFullName: "acme/shop" });
  expect(api.sent(posts)[0].body).toMatchObject({ slug: "shop-2", name: "shop" });
});

test("pasting a .env fills matching rows, adds the rest and never stores the values", async ({ page, api }) => {
  deployable(api);
  await page.goto("/new?org=acme&repo=acme/shop");
  await page.getByRole("button", { name: "Paste .env" }).click();
  await page.getByLabel("Paste a .env file").fill("DATABASE_URL=postgres://shop:hunter2@db.example.com/shop\nLOG_LEVEL=debug\nFEATURE_FLAGS=checkout");
  await page.getByRole("button", { name: "Add variables" }).click();
  await expect(page.getByText("Added 1, updated 2, skipped 0.")).toBeVisible();
  await expect(page.getByText("Updated from paste")).toHaveCount(2);
  await expect(page.getByText("3 of 7 filled.", { exact: false })).toBeVisible();
  await expect(page.getByText("1 required variable is empty.")).toBeVisible();
  const kept = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }, location.href]));
  expect(kept).not.toContain("hunter2");
  expect(kept).not.toContain("checkout");

  await page.getByRole("button", { name: "Deploy", exact: true }).click();
  await page.waitForURL(/tab=deployments/);
  expect((api.sent(posts)[0].body as { env: unknown }).env).toEqual([
    { name: "DATABASE_URL", value: "postgres://shop:hunter2@db.example.com/shop", secret: true },
    { name: "LOG_LEVEL", value: "debug", secret: false },
    { name: "FEATURE_FLAGS", value: "checkout", secret: false },
  ]);
});

test("a monorepo checks the apps that fit the plan, posts them one by one and retries only what is missing", async ({ page, api }) => {
  api.on("GET /me/github/repositories/detect", monorepo);
  api.on("GET /orgs/acme/usage", { ...usage, services: 3, replicas: 3 });
  deployable(api);
  let attempt = 0;
  api.on(posts, (body: unknown) => {
    const { slug } = body as ServiceSpec;
    attempt++;
    return attempt === 2 ? new Reply(409, { error: "plan_limit", message: "the free plan's services limit is 5" }) : new Reply(201, { ...service, slug, name: slug, buildId: `build-${attempt}` });
  });
  await page.goto("/new?org=acme&repo=acme/shop");
  await expect(page.getByRole("checkbox", { name: "Deploy web" })).toBeChecked();
  await expect(page.getByRole("checkbox", { name: "Deploy api" })).toBeChecked();
  await expect(page.getByRole("checkbox", { name: "Deploy docs" })).not.toBeChecked();
  await expect(page.getByText("Your Free plan has room for 2 more services.")).toBeVisible();
  await expect(page.getByText("Builds run one at a time on the Free plan.")).toBeVisible();

  await page.getByRole("button", { name: "Deploy 2 apps" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "the free plan's services limit is 5" })).toBeVisible();
  await expect(page.getByText("Project shop is created", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Deploy 2 apps" }).click();
  await page.waitForURL("/acme/shop");

  expect(api.sent("POST /orgs/acme/projects")).toHaveLength(1);
  expect(api.sent(posts).map((call) => (call.body as ServiceSpec).slug)).toEqual(["web", "api", "api"]);
  expect(api.sent(posts).map((call) => (call.body as ServiceSpec).buildCommand)).toEqual(["pnpm --filter web build", "pnpm --filter api build", "pnpm --filter api build"]);
  const order = api.calls.map((call) => `${call.method} ${call.path}`).filter((key) => key.startsWith("POST") || key.endsWith("/environments"));
  expect(order).toEqual(["POST /orgs/acme/projects", `GET /projects/${project.id}/environments`, posts, posts, `GET /projects/${project.id}/environments`, posts]);
});

test("a repository that cannot be read opens plain settings and still deploys", async ({ page, api }) => {
  const warning = "Couldn't read acme/shop, so nothing was detected. Railpack will still detect the stack during the build.";
  api.on("GET /me/github/repositories/detect", { ...detection, commit: null, partial: true, services: [], directories: [], warnings: [warning] });
  await page.goto("/new?org=acme&repo=acme/shop");
  await expect(page.getByText(warning)).toBeVisible();
  await expect(page.getByRole("form", { name: "Settings for shop" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Deploy", exact: true })).toBeEnabled();
});

test("a slow read enables Deploy after five seconds", async ({ page, api }) => {
  api.on("GET /me/github/repositories/detect", () => new Promise((resolve) => setTimeout(() => resolve(detection), 8000)));
  await page.goto("/new?org=acme&repo=acme/shop");
  await expect(page.getByRole("button", { name: "Reading repository…" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Deploy", exact: true })).toBeEnabled({ timeout: 6000 });
  await expect(page.getByText("Still reading acme/shop. Railpack will detect the stack during the build.")).toBeVisible();
  await expect(page.getByText("From package.json (next) and pnpm-lock.yaml")).toBeVisible({ timeout: 10000 });
});

test("adding a service to a project hides the app that is already deployed and offers a database for DATABASE_URL", async ({ page, api }) => {
  const [deployed, worker] = projectDetection.services;
  const databaseUrl = { name: "DATABASE_URL", description: null, source: ".env.example", required: false, secretHint: false };
  api.on(`GET /projects/${project.id}/detect`, { ...projectDetection, services: [deployed, { ...worker, variables: [databaseUrl] }] });
  await page.goto("/new?org=acme&project=shop");
  await expect(page.getByRole("heading", { name: "Add a service to Shop" })).toBeVisible();
  await expect(page.getByText("Worker · worker · Railpack")).toBeVisible();
  await expect(page.getByText("Next.js")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Add a database" })).toHaveAttribute("href", "/acme/shop");
  expect(api.sent(`GET /projects/${project.id}/detect`)[0].search).toBe("?ref=main");
});

test("adding a service opens plain settings when the branch cannot be read", async ({ page, api }) => {
  api.on(`GET /projects/${project.id}/detect`, new Reply(422, { error: "invalid", message: "ref must be a branch name or commit sha", field: "ref" }));
  await page.goto("/new?org=acme&project=shop");
  await expect(page.getByText("Couldn't read acme/shop: ref must be a branch name or commit sha. Railpack will still detect the stack during the build.")).toBeVisible();
  await expect(page.getByRole("form", { name: "Settings for web" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Deploy", exact: true })).toBeEnabled();
});

test("check repository adds the names the service has not set as empty rows", async ({ page, api }) => {
  await page.goto(`${servicePath}?tab=env`);
  await page.getByRole("button", { name: "Check repository" }).click();
  await expect(page.getByText("Found in .env.example: 2 not set")).toBeVisible();
  await page.getByRole("button", { name: "Add all" }).click();
  await expect(page.getByLabel("Name")).toHaveCount(4);
  await expect(page.getByLabel("Name").nth(2)).toHaveValue("STRIPE_SECRET_KEY");
  await expect(page.getByLabel("Value").nth(2)).toHaveValue("");
  expect(api.sent(`GET /projects/${project.id}/detect`)).toHaveLength(1);
});
