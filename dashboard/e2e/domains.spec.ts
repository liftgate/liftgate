import type { Domain } from "../src/lib/types";
import { expect, platformDomain, Reply, service, servicePath, test } from "./fixtures";

const custom: Domain = {
  id: "domain-custom",
  serviceId: service.id,
  hostname: "shop.example.com",
  kind: "custom",
  verificationToken: "liftgate-verify-7f3a",
  verifiedAt: null,
  certificateStatus: "pending",
  certificateMessage: null,
  dnsRecords: [
    { type: "TXT", name: "_liftgate.shop.example.com", value: "liftgate-verify-7f3a" },
    { type: "CNAME", name: "shop.example.com", value: platformDomain.hostname },
  ],
};

test("a custom domain is added, lists its DNS records, verifies and is removed after confirming", async ({ page, api }) => {
  let domains = [platformDomain];
  api.on(`GET /services/${service.id}/domains`, () => domains);
  api.on(`POST /services/${service.id}/domains`, () => {
    domains = [platformDomain, custom];
    return new Reply(201, custom);
  });
  api.on(`POST /domains/${custom.id}/verify`, () => {
    domains = [platformDomain, { ...custom, verifiedAt: new Date().toISOString(), certificateStatus: "ready" }];
    return domains[1];
  });
  api.on(`DELETE /domains/${custom.id}`, () => {
    domains = [platformDomain];
  });
  await page.goto(`${servicePath}?tab=domains`);
  await page.getByPlaceholder("app.example.com").fill(custom.hostname);
  await page.getByRole("button", { name: "Add domain" }).click();
  await expect(page.getByLabel("TXT name")).toHaveValue("_liftgate.shop.example.com");
  await expect(page.getByLabel("TXT value")).toHaveValue("liftgate-verify-7f3a");
  await expect(page.getByLabel("CNAME target")).toHaveValue(platformDomain.hostname);
  expect(api.sent(`POST /services/${service.id}/domains`)[0].body).toEqual({ hostname: custom.hostname });

  const row = page.getByRole("row", { name: /^shop\.example\.com/ });
  await row.getByRole("button", { name: "Verify" }).click();
  await expect(row).toContainText("verified");
  await expect(row).toContainText("ready");
  await expect(page.getByLabel("TXT name")).toHaveCount(0);

  await row.getByRole("button", { name: "Remove" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Remove domain" }).click();
  await expect(row).toHaveCount(0);
  expect(api.sent(`DELETE /domains/${custom.id}`)).toHaveLength(1);
});
