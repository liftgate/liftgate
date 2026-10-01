import { asOperator, expect, Reply, test, waiting } from "./fixtures";

test("an operator approves a pending account after confirming", async ({ page, api }) => {
  await asOperator(api);
  let approved = false;
  api.on("GET /operator/users", () => (approved ? [] : [waiting]));
  api.on(`POST /operator/users/${waiting.user.id}/approve`, () => {
    approved = true;
    return { message: "grace is active" };
  });
  await page.goto("/dashboard/operator");
  await page.getByRole("row", { name: /grace/ }).getByRole("button", { name: "Approve" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Approve account" }).click();
  await expect(page.getByRole("status")).toHaveText("grace is active");
  await expect(page.getByText("No accounts are waiting for approval")).toBeVisible();
  expect(api.sent(`POST /operator/users/${waiting.user.id}/approve`)).toHaveLength(1);
});

test("an operator moves an organization to another plan and suspends it with a reason", async ({ page, api }) => {
  await asOperator(api);
  api.on("PUT /operator/orgs/acme/plan", { message: "acme is on the unlimited plan" });
  api.on("POST /operator/orgs/acme/suspend", { message: "acme is suspended" });
  await page.goto("/dashboard/operator?view=orgs");
  await page.getByLabel("Plan of acme").selectOption("unlimited");
  await expect(page.getByRole("status")).toHaveText("acme is on the unlimited plan");
  expect(api.sent("PUT /operator/orgs/acme/plan")[0].body).toEqual({ plan: "unlimited" });

  await page.getByRole("row", { name: /Acme/ }).getByRole("button", { name: "Suspend" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Reason").fill("phishing");
  await dialog.getByRole("button", { name: "Suspend organization" }).click();
  await expect(page.getByRole("status")).toHaveText("acme is suspended");
  expect(api.sent("POST /operator/orgs/acme/suspend")[0].body).toEqual({ reason: "phishing" });
});

test("the console is not offered to anyone but operators", async ({ page, api }) => {
  api.on("GET /operator/summary", new Reply(404));
  await page.goto("/account");
  await expect(page.getByRole("link", { name: "Operator console" })).toHaveCount(0);
  await expect(page.locator('nav[aria-label="Main"] a[href="/dashboard/operator"]')).toHaveCount(0);
  await page.goto("/dashboard/operator");
  await expect(page.getByText("Page not found")).toBeVisible();
  expect(api.calls.filter((call) => call.path.startsWith("/operator/") && call.path !== "/operator/summary")).toEqual([]);
});

test("the console is a plain 404 page without operator copy for anyone but an operator", async ({ request }) => {
  const get = (session?: string) => request.get("/dashboard/operator", { headers: session ? { cookie: `liftgate_session=${session}` } : {} });
  for (const session of [undefined, "member"]) {
    const response = await get(session);
    expect(response.status(), session).toBe(404);
    const html = await response.text();
    expect(html).toContain("<title>Page not found · Liftgate</title>");
    expect(html).not.toContain("Operator");
    expect(html).not.toContain("Approve new accounts");
  }
  const console = await get("operator");
  expect(console.status()).toBe(200);
  expect(await console.text()).toContain("Approve new accounts");
});
