import type { Organization, Project, ProjectTree } from "../src/lib/types";
import { build, environment, expect, org, project, Reply, service, test } from "./fixtures";

test("a new user goes from no organization to a streaming build without typing a repository name", async ({ page, api }) => {
  const created = { ...service, slug: "shop", name: "Shop", current: null };
  let orgs: Organization[] = [];
  let projects: Project[] = [];
  let tree: ProjectTree = { project, environments: [environment], services: [] };
  api.on("GET /orgs", () => orgs);
  api.on("POST /orgs", () => {
    orgs = [org];
    return new Reply(201, org);
  });
  api.on("GET /orgs/acme/projects", () => projects);
  api.on("POST /orgs/acme/projects", () => {
    projects = [project];
    return new Reply(201, project);
  });
  api.on("GET /orgs/acme/projects/shop/tree", () => tree);
  api.on(`POST /environments/${environment.id}/services`, () => {
    tree = { ...tree, services: [created] };
    return new Reply(201, { ...created, buildId: "build-2" });
  });
  api.on(`GET /services/${service.id}/builds`, [{ ...build, id: "build-2", status: "running", finishedAt: null }]);

  await page.goto("/dashboard");
  await page.getByLabel("Name").fill("Acme");
  await page.getByRole("button", { name: "Create organization" }).click();
  await page.waitForURL("/acme");

  await page.getByRole("button", { name: "New project" }).first().click();
  await page.getByRole("radio", { name: /acme\/shop/ }).check();
  await expect(page.getByLabel("Slug")).toHaveValue("shop");
  await page.getByRole("button", { name: "Create project" }).click();
  await page.waitForURL("/acme/shop?new=service");

  await expect(page.getByRole("heading", { name: "Configure and deploy" })).toBeVisible();
  await page.getByRole("button", { name: "Deploy" }).click();
  await page.waitForURL("/acme/shop/production/shop?tab=builds&build=build-2");
  await expect(page.getByRole("region", { name: /Build 4f2a9c1 output/ })).toContainText("Listening on port 8080");
  await expect(page.getByText("Live")).toBeVisible();

  expect(api.sent("POST /orgs/acme/projects")[0].body).toEqual({ slug: "shop", name: "shop", repoFullName: "acme/shop" });
  expect(api.sent(`POST /environments/${environment.id}/services`)[0].search).toBe("?deploy=true");
});
