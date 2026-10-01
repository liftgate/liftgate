import { expect, org, Reply, test } from "./fixtures";

test("the first organization takes the user's name, hides its URL name until asked and opens a slug error", async ({ page, api }) => {
  api.on("GET /orgs", []);
  api.on("POST /orgs", new Reply(422, { error: "invalid", message: "the slug new is reserved", field: "slug" }));
  await page.goto("/dashboard");
  await expect(page.getByLabel("Name")).toHaveValue("Ada Lovelace");
  await expect(page.getByLabel("URL name")).toHaveCount(0);
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await expect(page.getByLabel("URL name")).toHaveValue("ada-lovelace");
  await page.getByLabel("URL name").fill("new");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("the slug new is reserved")).toBeVisible();
  expect(api.sent("POST /orgs")[0].body).toEqual({ slug: "new", name: "Ada Lovelace" });

  api.on("POST /orgs", new Reply(201, org));
  await page.getByLabel("URL name").fill("acme");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.waitForURL("/new?org=acme");
});

test("old onboarding links and a fresh GitHub App install land on the import screen", async ({ page }) => {
  for (const [from, to] of [
    ["/dashboard?installed=1", "/new?org=acme"],
    ["/acme?new=project", "/new?org=acme"],
    ["/acme/shop?new=service", "/new?org=acme&project=shop"],
  ]) {
    await page.goto(from);
    await page.waitForURL(to);
  }
  await expect(page.getByRole("heading", { name: "Add a service to Shop" })).toBeVisible();
});
