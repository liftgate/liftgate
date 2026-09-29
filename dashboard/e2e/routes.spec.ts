import AxeBuilder from "@axe-core/playwright";
import { expect, servicePath, settled, test, type Api } from "./fixtures";

const service = "web · production · shop · Liftgate";

const routes: { path: string; title: string; setup?: (api: Api) => void }[] = [
  { path: "/", title: "Liftgate: open-source hosting for full-stack apps" },
  { path: "/login", title: "Sign in · Liftgate" },
  { path: "/login/sso", title: "SAML single sign-on · Liftgate" },
  { path: "/dashboard", title: "Dashboard · Liftgate", setup: (api) => api.on("GET /orgs", []) },
  { path: "/account", title: "Account · Liftgate" },
  { path: "/account/invitations/welcome", title: "Invitation · Liftgate" },
  { path: "/acme", title: "acme · Liftgate" },
  { path: "/acme/settings", title: "Settings · acme · Liftgate" },
  { path: "/acme/settings/members", title: "Members · acme · Liftgate" },
  { path: "/acme/settings/audit", title: "Audit log · acme · Liftgate" },
  { path: "/acme/settings/tokens", title: "API tokens · acme · Liftgate" },
  { path: "/acme/settings/sso", title: "SAML SSO · acme · Liftgate" },
  { path: "/acme/settings/notifications", title: "Notifications · acme · Liftgate" },
  { path: "/acme/shop", title: "shop · acme · Liftgate" },
  ...["deployments", "logs", "metrics", "builds", "env", "domains", "settings"].map((tab) => ({ path: `${servicePath}?tab=${tab}`, title: service })),
  { path: `${servicePath}/missing`, title: "Page not found · Liftgate" },
];

for (const route of routes) {
  test(`${route.path} has a title, passes axe and fits the viewport`, async ({ page, api }) => {
    route.setup?.(api);
    await page.goto(route.path);
    await settled(page);
    await expect(page).toHaveTitle(route.title);
    const { violations } = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
    const blocking = violations.filter((v) => v.impact === "serious" || v.impact === "critical");
    expect(blocking.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(await page.evaluate(() => window.innerWidth));
  });
}
