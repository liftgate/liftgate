import { expect, service, servicePath, test } from "./fixtures";

test("deleting a service waits for its slug to be typed", async ({ page, api }) => {
  api.on(`DELETE /services/${service.id}`, undefined);
  await page.goto(`${servicePath}?tab=settings`);
  await page.getByRole("button", { name: "Delete service" }).click();
  const dialog = page.getByRole("dialog");
  const confirm = dialog.getByRole("button", { name: "Delete service" });
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel(`Type ${service.slug} to confirm`).fill(service.slug);
  await confirm.click();
  await page.waitForURL("/acme/shop");
  expect(api.sent(`DELETE /services/${service.id}`)).toHaveLength(1);
});

test("cancelling a confirmation sends nothing", async ({ page, api }) => {
  await page.goto("/account");
  await page.getByRole("button", { name: "Disconnect" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
  expect(api.calls.filter((call) => call.method === "DELETE")).toEqual([]);
});
