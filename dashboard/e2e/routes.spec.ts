import AxeBuilder from "@axe-core/playwright";
import { asOperator, expect, openNav, servicePath, settled, test, type Api } from "./fixtures";

const service = "web · production · shop · Liftgate";

const routes: { path: string; title: string; current?: RegExp; parent?: string; setup?: (api: Api) => unknown }[] = [
  { path: "/", title: "Liftgate: open-source hosting for full-stack apps", setup: (api) => api.signOut() },
  { path: "/login", title: "Sign in · Liftgate" },
  { path: "/login/sso", title: "SAML single sign-on · Liftgate" },
  { path: "/dashboard", title: "Dashboard · Liftgate", setup: (api) => api.on("GET /orgs", []) },
  ...["pending", "users", "orgs"].map((view) => ({ path: `/dashboard/operator?view=${view}`, title: "Operator · Liftgate", current: /^Operator/, setup: asOperator })),
  { path: "/new?org=acme", title: "Import a repository · Liftgate", current: /^Projects$/ },
  { path: "/new?org=acme&repo=acme/shop", title: "Deploy acme/shop · Liftgate", current: /^Projects$/ },
  { path: "/new?org=acme&project=shop", title: "Add a service · Liftgate", current: /^Overview$/, parent: "/acme" },
  { path: "/account", title: "Account · Liftgate", current: /ada/ },
  { path: "/account?section=sign-in", title: "Account · Liftgate", current: /ada/ },
  { path: "/account?section=git", title: "Account · Liftgate", current: /ada/ },
  { path: "/account/invitations/welcome", title: "Invitation · Liftgate", current: /ada/ },
  { path: "/acme", title: "acme · Liftgate", current: /^Projects$/ },
  { path: "/acme/settings", title: "General · acme · Liftgate", current: /^Settings$/ },
  { path: "/acme/settings/members", title: "Members · acme · Liftgate", current: /^Settings$/, parent: "/acme" },
  { path: "/acme/settings/audit", title: "Audit log · acme · Liftgate", current: /^Settings$/ },
  { path: "/acme/settings/tokens", title: "API tokens · acme · Liftgate", current: /^Settings$/ },
  { path: "/acme/settings/sso", title: "SAML SSO · acme · Liftgate", current: /^Settings$/ },
  { path: "/acme/settings/notifications", title: "Notifications · acme · Liftgate", current: /^Settings$/ },
  { path: "/acme/shop", title: "shop · acme · Liftgate", current: /^Overview$/, parent: "/acme" },
  { path: "/acme/shop/settings", title: "Settings · shop · acme · Liftgate", current: /^Settings$/, parent: "/acme/shop" },
  ...["deployments", "logs", "metrics", "env", "domains", "settings", "settings&section=build", "settings&section=runtime", "settings&section=resources", "builds&build=build-1"].map(
    (tab) => ({ path: `${servicePath}?tab=${tab}`, title: service, current: /^Web/, parent: "/acme/shop" }),
  ),
  { path: `${servicePath}/missing`, title: "Page not found · Liftgate" },
];

for (const route of routes) {
  test(`${route.path} has a title, passes axe and fits the viewport`, async ({ page, api }) => {
    await route.setup?.(api);
    await page.goto(route.path);
    await settled(page);
    await expect(page).toHaveTitle(route.title);
    const { violations } = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
    const blocking = violations.filter((v) => v.impact === "serious" || v.impact === "critical");
    expect(blocking.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(await page.evaluate(() => window.innerWidth));
    if (!route.current) return;
    const nav = await openNav(page);
    await expect(nav.locator('[aria-current="page"]')).toHaveCount(1);
    await expect(nav.locator('[aria-current="page"]')).toHaveAccessibleName(route.current);
    if (route.parent) await expect(nav.locator(`a[href="${route.parent}"]`)).toBeVisible();
  });
}
