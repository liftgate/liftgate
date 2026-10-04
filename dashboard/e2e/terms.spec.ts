import AxeBuilder from "@axe-core/playwright";
import { asOperator, expect, openNav, org, test, user } from "./fixtures";

const entries = [
  { path: "/dashboard", heading: "Projects", landing: `/${org.slug}` },
  { path: `/${org.slug}`, heading: "Projects" },
  { path: "/dashboard/operator", heading: "Operator" },
];

for (const { path, heading, landing = path } of entries) {
  test(`an account that never accepted the terms accepts them before ${path} opens, operators included`, async ({ page, api }) => {
    await asOperator(api);
    let accepted = false;
    api.on("GET /me", () => ({ ...user, operator: true, termsPending: !accepted }));
    api.on("POST /me/terms", () => {
      accepted = true;
    });
    await page.goto(path);
    await expect(page.getByText("By continuing you agree to the Terms and Acceptable Use Policy.")).toBeVisible();
    await expect(page.getByRole("heading", { name: heading, exact: true })).toHaveCount(0);
    const { violations } = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
    expect(violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => v.id)).toEqual([]);
    await page.getByRole("button", { name: "Accept" }).click();
    await page.waitForURL(landing);
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible();
    expect(api.sent("POST /me/terms")).toHaveLength(1);
  });
}

test("the account page stays open before the terms are accepted", async ({ page, api }) => {
  api.on("GET /me", { ...user, termsPending: true });
  await page.goto(`/${org.slug}`);
  await expect(page.getByRole("button", { name: "Accept" })).toBeVisible();
  await (await openNav(page)).getByRole("button", { name: /Account: ada/ }).click();
  await page.getByRole("menuitem", { name: "Account settings" }).click();
  await expect(page.getByRole("heading", { name: "Account", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept" })).toHaveCount(0);
});
