import AxeBuilder from "@axe-core/playwright";
import { asOperator, expect, org, test, user } from "./fixtures";

test("an account that never accepted the terms accepts them before the dashboard opens, operators included", async ({ page, api }) => {
  await asOperator(api);
  let accepted = false;
  api.on("GET /me", () => ({ ...user, operator: true, termsPending: !accepted }));
  api.on("POST /me/terms", () => {
    accepted = true;
  });
  await page.goto("/dashboard");
  await expect(page.getByText("By continuing you agree to the Terms and Acceptable Use Policy.")).toBeVisible();
  const { violations } = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
  expect(violations.filter((v) => v.impact === "serious" || v.impact === "critical").map((v) => v.id)).toEqual([]);
  await page.getByRole("button", { name: "Accept" }).click();
  await page.waitForURL(`/${org.slug}`);
  expect(api.sent("POST /me/terms")).toHaveLength(1);
});
