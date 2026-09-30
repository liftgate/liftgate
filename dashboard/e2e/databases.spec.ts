import type { Database } from "../src/lib/types";
import { database, deployment, environment, expect, Reply, service, test } from "./fixtures";

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
  api.on(`POST /databases/${database.id}/restore`, new Reply(201, { ...database, id: "database-restored", slug: "main-restored" }));

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
  await expect(details).toBeHidden();
  const restore = api.sent(`POST /databases/${database.id}/restore`)[0].body as { slug: string; pointInTime: string };
  expect(restore.slug).toBe("main-restored");
  expect(new Date(restore.pointInTime).getTime()).toBe(new Date("2026-09-30T12:00:00").getTime());
});
