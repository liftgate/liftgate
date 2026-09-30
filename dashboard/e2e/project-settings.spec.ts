import type { PreviewStatus } from "../src/lib/types";
import { expect, project, test } from "./fixtures";

const fork = {
  number: 12,
  title: "Add checkout",
  headRef: "checkout",
  headSha: "9c1e7b3d5a6f8e9c0b1a2d3e4f5a6b7c8d9e4f2a",
  fork: true,
  approvedSha: null,
  error: null,
  updatedAt: new Date().toISOString(),
};

test("an admin turns previews on, approves a fork's head commit and deletes the project after typing its slug", async ({ page, api }) => {
  const status: PreviewStatus = { missing: ["Issues: Read and write"], pullRequests: [fork] };
  api.on(`GET /projects/${project.id}/previews`, status);
  api.on(`PATCH /projects/${project.id}`, { ...project, previewsEnabled: true });
  api.on(`POST /projects/${project.id}/previews/approve`, undefined);
  api.on(`DELETE /projects/${project.id}`, undefined);
  await page.goto("/acme/shop/settings");
  await expect(page.getByText(/Issues: Read and write/)).toBeVisible();

  await page.getByLabel("Deploy a preview of every pull request").check();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Saved." })).toBeVisible();
  expect(api.sent(`PATCH /projects/${project.id}`).map((call) => call.body)).toEqual([{ previewsEnabled: true, previewBaseEnvironmentId: null }]);

  await page.getByRole("row", { name: /#12 Add checkout/ }).getByRole("button", { name: "Approve 9c1e7b3" }).click();
  await expect.poll(() => api.sent(`POST /projects/${project.id}/previews/approve`).map((call) => call.body)).toEqual([{ number: 12, sha: fork.headSha }]);

  await page.getByRole("button", { name: "Delete project" }).click();
  const dialog = page.getByRole("dialog");
  const confirm = dialog.getByRole("button", { name: "Delete project" });
  await expect(confirm).toBeDisabled();
  await dialog.getByLabel(`Type ${project.slug} to confirm`).fill(project.slug);
  await confirm.click();
  await page.waitForURL("/acme");
  expect(api.sent(`DELETE /projects/${project.id}`)).toHaveLength(1);
});
