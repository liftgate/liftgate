import type { EnvVar } from "../src/lib/types";
import { deployment, expect, Reply, service, servicePath, test } from "./fixtures";

const put = `PUT /services/${service.id}/env`;

test("saving variables keeps a stored secret, sends new values and redeploys only when asked", async ({ page, api }) => {
  api.on(put, (body: unknown) => (body as EnvVar[]).map((v) => (v.secret ? { ...v, value: null } : v)));
  api.on(`POST /services/${service.id}/redeploy`, new Reply(201, deployment));
  await page.goto(`${servicePath}?tab=env`);
  const names = page.getByLabel("Name");
  const values = page.getByLabel("Value");
  await expect(values.first()).toHaveAttribute("placeholder", "Hidden. Type to replace.");
  await expect(page.getByRole("checkbox", { name: "Secret" }).first()).toBeDisabled();

  await page.getByRole("button", { name: "Add variable" }).click();
  await names.nth(2).fill("FEATURE_FLAGS");
  await values.nth(2).fill("checkout");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await page.getByRole("tabpanel").getByRole("button", { name: "Redeploy", exact: true }).click();
  await expect(page.getByText("Saved. Redeploying without a rebuild.")).toBeVisible();
  expect(api.sent(put)[0].body).toEqual([
    { name: "DATABASE_URL", value: null, secret: true },
    { name: "LOG_LEVEL", value: "info", secret: false },
    { name: "FEATURE_FLAGS", value: "checkout", secret: false },
  ]);
  expect(api.sent(`POST /services/${service.id}/redeploy`)).toHaveLength(1);

  await values.first().fill("postgres://db.example.com/shop");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: /^Saved\.$/ })).toBeVisible();
  expect((api.sent(put)[1].body as unknown[])[0]).toEqual({ name: "DATABASE_URL", value: "postgres://db.example.com/shop", secret: true });
  expect(api.sent(`POST /services/${service.id}/redeploy`)).toHaveLength(1);
});
