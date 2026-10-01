import type { Database } from "../src/lib/types";
import { database, deployment, environment, expect, providers, Reply, service, servicePath, test } from "./fixtures";

const list = `GET /environments/${environment.id}/databases`;

test("a database is added, linked with a redeploy, reveals its connection and restores into a new one", async ({ page, api }) => {
  let databases: Database[] = [database];
  const uri = "postgresql://app:secret@main-rw.env-acme-shop:5432/app";
  api.on(list, () => databases);
  api.on(`POST /environments/${environment.id}/databases`, (body: unknown) => {
    const added = { ...database, id: "database-cache", slug: (body as Database).slug, ready: false };
    databases = [...databases, added];
    return new Reply(201, added);
  });
  api.on(`POST /databases/${database.id}/links`, () => {
    databases = databases.map((d) => (d.id === database.id ? { ...d, links: [{ serviceId: service.id, envName: "DATABASE_URL" }] } : d));
  });
  api.on(`POST /services/${service.id}/redeploy`, new Reply(201, deployment));
  api.on(`GET /databases/${database.id}/connection`, { uri });
  const limit = "the free plan's CPU limit is 1000m across the organization, and this change needs 1500m";
  api.on(`POST /databases/${database.id}/restore`, () =>
    api.sent(`POST /databases/${database.id}/restore`).length === 1
      ? new Reply(409, { error: "plan_limit", message: limit, field: "cpuMillis" })
      : new Reply(201, { ...database, id: "database-restored", slug: "main-restored" }),
  );

  await page.goto("/acme/shop");
  await page.getByRole("button", { name: "Add database" }).click();
  const add = page.getByRole("dialog");
  await add.getByLabel("Name").fill("cache");
  await add.getByLabel("Storage (GB)").fill("2");
  await add.getByRole("button", { name: "Add database" }).click();
  await expect(page.getByRole("region", { name: "Databases in Production" }).getByText("starting")).toBeVisible();
  expect(api.sent(`POST /environments/${environment.id}/databases`)[0].body).toEqual({ slug: "cache", storageGb: 2, cpuMillis: 500, memoryMb: 512 });

  await page.getByRole("row", { name: /main/ }).getByRole("button", { name: "Manage" }).click();
  const details = page.getByRole("dialog");
  await expect(details.getByLabel("Connection URL")).toHaveValue(uri);
  await expect(details.getByRole("region", { name: "Backups of main" }).getByText("completed")).toBeVisible();
  await details.getByRole("button", { name: "Link and redeploy" }).click();
  await expect(details.getByText("Saved. Redeploying without a rebuild.")).toBeVisible();
  expect(api.sent(`POST /databases/${database.id}/links`)[0].body).toEqual({ serviceId: service.id, envName: "DATABASE_URL" });
  expect(api.sent(`POST /services/${service.id}/redeploy`)).toHaveLength(1);
  await expect(details.getByText("Web as DATABASE_URL")).toBeVisible();

  await details.getByLabel("Restore to").fill("2026-09-30T12:00");
  await details.getByRole("button", { name: "Restore into a new database" }).click();
  await expect(details.getByText(limit)).toBeVisible();
  await details.getByRole("button", { name: "Restore into a new database" }).click();
  await expect(details).toBeHidden();
  const restore = api.sent(`POST /databases/${database.id}/restore`)[1].body as { slug: string; pointInTime: string };
  expect(restore.slug).toBe("main-restored");
  expect(new Date(restore.pointInTime).getTime()).toBe(new Date("2026-09-30T12:00:00").getTime());
});

test("without a storage class databases and volumes explain why instead of offering forms", async ({ page, api }) => {
  api.on("GET /auth/providers", { ...providers, storage: false });
  api.on(list, []);
  await page.goto("/acme/shop");
  const databases = page.getByRole("region", { name: "Databases", exact: true });
  await expect(databases.getByText("Databases need a storage class that enforces capacity")).toBeVisible();
  await expect(databases.getByRole("link", { name: "Storage in the self-hosting guide" })).toHaveAttribute("href", /documentation\/self-hosting\.md$/);
  await expect(page.getByRole("button", { name: "Add database" })).toHaveCount(0);

  await page.goto(`${servicePath}?tab=settings`);
  await page.locator("summary", { hasText: "Resources" }).click();
  await expect(page.getByText("Volumes need a storage class that enforces capacity")).toBeVisible();
  await expect(page.getByLabel("Volume path")).toHaveCount(0);
});
