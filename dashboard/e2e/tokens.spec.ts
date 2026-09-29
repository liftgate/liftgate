import type { ApiToken } from "../src/lib/types";
import { expect, Reply, test, user } from "./fixtures";

const token: ApiToken = { id: "token-ci", name: "github-actions", createdBy: user, createdAt: new Date().toISOString(), lastUsedAt: null, expiresAt: null };

test("an API token is shown once on creation and revoked after confirming", async ({ page, api }) => {
  let tokens: ApiToken[] = [];
  api.on("GET /orgs/acme/tokens", () => tokens);
  api.on("POST /orgs/acme/tokens", () => {
    tokens = [token];
    return new Reply(201, { token: "lg_example_only_shown_once" });
  });
  api.on(`DELETE /orgs/acme/tokens/${token.id}`, () => {
    tokens = [];
  });
  await page.goto("/acme/settings/tokens");
  await page.getByRole("button", { name: "New token" }).first().click();
  await page.getByLabel("Name").fill(token.name);
  await page.getByLabel("Expires").selectOption({ label: "30 days" });
  await page.getByRole("button", { name: "Create token" }).click();
  await expect(page.getByLabel("Token")).toHaveValue("lg_example_only_shown_once");
  expect(api.sent("POST /orgs/acme/tokens")[0].body).toEqual({ name: token.name, expiresInDays: 30 });
  await page.getByRole("button", { name: "Done" }).click();

  await page.getByRole("row", { name: /github-actions/ }).getByRole("button", { name: "Revoke" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Revoke token" }).click();
  await expect(page.getByText("No API tokens")).toBeVisible();
  expect(api.sent(`DELETE /orgs/acme/tokens/${token.id}`)).toHaveLength(1);
});
