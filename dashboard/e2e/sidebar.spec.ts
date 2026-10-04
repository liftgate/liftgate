import AxeBuilder from "@axe-core/playwright";
import type { Environment, Organization, Service } from "../src/lib/types";
import { asOperator, build, environment, expect, openNav, org, project, Reply, service, servicePath, settled, test } from "./fixtures";

test("the first organization shows in the switcher without a reload", async ({ page, api }) => {
  let orgs: Organization[] = [];
  api.on("GET /orgs", () => orgs);
  api.on("POST /orgs", () => {
    orgs = [org];
    return new Reply(201, org);
  });
  await page.goto("/dashboard");
  await page.evaluate(() => Object.assign(window, { untouched: true }));
  await page.getByRole("button", { name: "Continue" }).click();
  await page.waitForURL("/new?org=acme");
  const nav = await openNav(page);
  await expect(nav.getByRole("button", { name: /Organization: Acme/ })).toBeVisible();
  expect(await page.evaluate(() => "untouched" in window)).toBe(true);
});

test("the organization and project switchers open with Enter, move with the arrow keys and close with Escape", async ({ page, api }) => {
  api.on("GET /orgs", [org, { ...org, id: "org-globex", slug: "globex", name: "Globex", role: "member" }]);
  await page.goto(servicePath);
  await settled(page);
  const nav = await openNav(page);
  for (const [name, count] of [
    [/Organization: Acme/, 4],
    [/Project: Shop/, 3],
  ] as const) {
    const button = nav.getByRole("button", { name });
    await button.focus();
    await page.keyboard.press("Enter");
    await expect(button).toHaveAttribute("aria-expanded", "true");
    const items = nav.getByRole("menu").locator("[role^=menuitem]");
    await expect(items).toHaveCount(count);
    await expect(items.first()).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await expect(items.nth(1)).toBeFocused();
    await expect(items.nth(1)).toHaveCSS("outline", "rgb(207, 67, 252) solid 2px");
    await page.keyboard.press("End");
    await expect(items.last()).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await expect(items.first()).toBeFocused();
    await page.keyboard.press("ArrowUp");
    await expect(items.last()).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(nav.getByRole("menu")).toHaveCount(0);
    await expect(button).toBeFocused();
    await expect(button).toHaveAttribute("aria-expanded", "false");
  }
  await expect(nav).toBeVisible();
});

test("below 1024 px the sidebar is a drawer that keeps focus and closes on Escape, on a tap beside it and on navigation", async ({ page }) => {
  test.skip((page.viewportSize()?.width ?? 1440) >= 1024, "the sidebar is always open from 1024 px");
  await page.goto(servicePath);
  await settled(page);
  const menu = page.getByRole("button", { name: "Open menu" });
  await menu.click();
  const drawer = page.getByRole("dialog", { name: "Menu" });
  await expect(drawer.getByRole("navigation", { name: "Main" })).toBeVisible();
  const { violations } = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
  expect(violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => v.id)).toEqual([]);
  for (let i = 0; i < 16; i++) {
    await page.keyboard.press("Tab");
    expect(await page.evaluate(() => !document.hasFocus() || !!document.activeElement?.closest("dialog[open]")), `tab ${i}`).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  await expect(menu).toBeFocused();
  await menu.click();
  await page.mouse.click(340, 400);
  await expect(drawer).toBeHidden();
  await menu.click();
  await drawer.getByRole("link", { name: "Overview" }).click();
  await page.waitForURL("/acme/shop");
  await expect(drawer).toBeHidden();
});

test("at project scope there is one Settings item, services group by environment and show their status as text", async ({ page, api }) => {
  const staging: Environment = { ...environment, id: "environment-staging", slug: "staging", name: "Staging", kind: "preview", branch: "develop" };
  const preview: Environment = { ...environment, id: "environment-pr-12", slug: "pr-12", name: "PR #12", kind: "preview", branch: "checkout", pullRequest: 12 };
  const worker: Service = { ...service, id: "service-worker", slug: "worker", name: "Worker", environmentId: staging.id, current: { deploymentId: "deployment-worker", status: "failed", replicasReady: 0, commitSha: build.commitSha, createdAt: build.createdAt } };
  const previewWeb: Service = { ...service, id: "service-preview", environmentId: preview.id, current: null };
  api.on("GET /orgs/acme/projects/shop/tree", { project, environments: [preview, environment, staging], services: [service, worker, previewWeb] });
  await page.goto(`${servicePath}?tab=logs`);
  await settled(page);
  const nav = await openNav(page);
  await expect(nav.getByRole("link", { name: "Settings", exact: true })).toHaveCount(1);
  await expect(nav.getByText("Production", { exact: true })).toBeVisible();
  await expect(nav.getByText("Staging", { exact: true })).toBeVisible();
  await expect(nav.getByRole("link", { name: /^Web\W+running$/ })).toHaveAttribute("href", servicePath);
  await expect(nav.getByRole("link", { name: /^Worker\W+failed$/ })).toHaveAttribute("href", "/acme/shop/staging/worker");
  const previews = nav.getByText("Previews (1)");
  await expect(previews).toBeVisible();
  await expect(nav.getByRole("link", { name: /^Web\W+not deployed$/ })).toBeHidden();
  await previews.click();
  await expect(nav.getByRole("link", { name: /^Web\W+not deployed$/ })).toHaveAttribute("href", "/acme/shop/pr-12/web");
});

test("only operators see the operator console, with the number of accounts waiting", async ({ page, api }) => {
  await page.goto("/acme");
  await settled(page);
  let nav = await openNav(page);
  await expect(nav.getByRole("link", { name: /Operator/ })).toHaveCount(0);
  expect(api.sent("GET /operator/summary")).toEqual([]);
  await asOperator(api);
  await page.goto("/acme");
  await settled(page);
  nav = await openNav(page);
  await expect(nav.getByRole("link", { name: /^Operator ?1 pending$/ })).toHaveAttribute("href", "/dashboard/operator");
});

test("an account without an organization is offered one from the sidebar", async ({ page, api }) => {
  api.on("GET /orgs", []);
  await page.goto("/account");
  await settled(page);
  const nav = await openNav(page);
  await expect(nav.getByRole("link", { name: "Create organization" })).toHaveAttribute("href", "/dashboard");
  await expect(nav.getByRole("link", { name: "Projects" })).toHaveCount(0);
});
