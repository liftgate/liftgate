import { expect, Reply, service, servicePath, test } from "./fixtures";

test("a volume past the plan's storage limit shows the limit on its size", async ({ page, api }) => {
  const message = "the free plan's storage limit is 5 GB across the organization, and this change needs 6 GB";
  api.on(`PATCH /services/${service.id}`, new Reply(409, { error: "plan_limit", message, field: "storageGb" }));
  await page.goto(`${servicePath}?tab=settings`);
  await page.locator("summary", { hasText: "Resources" }).click();
  await page.getByLabel("Volume path").fill("/data");
  await page.getByLabel("Volume size (GB)").fill("6");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText(message)).toBeVisible();
  await expect(page.getByLabel("Volume size (GB)")).toHaveAttribute("aria-invalid", "true");
});
