import { expect, providers, Reply, test, user } from "./fixtures";

const only = (methods: Partial<typeof providers>) => ({ ...providers, oauth: [], passkey: false, email: false, sso: false, ...methods });

test("the sign-in page offers exactly the configured methods", async ({ page, api }) => {
  api.on("GET /auth/providers", only({ oauth: ["github", "google"], termsUrl: undefined }));
  await page.goto("/login?next=/acme");
  await expect(page.getByRole("link", { name: "Continue with GitHub" })).toHaveAttribute("href", "/api/v1/auth/github/login?next=%2Facme&intent=signin");
  await expect(page.getByRole("link", { name: "Continue with Google" })).toHaveAttribute("href", "/api/v1/auth/google/login?next=%2Facme&intent=signin");
  await expect(page.getByRole("button", { name: "Continue with passkey" })).toHaveCount(0);
  await expect(page.getByLabel("Email")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Continue with SAML SSO" })).toHaveCount(0);
  await expect(page.getByText("By continuing")).toHaveCount(0);
});

test("the sign-in page links the terms and the other methods when they are configured", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("link", { name: "Continue with GitHub" })).toBeVisible();
  await expect(page.getByLabel("Email")).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with passkey" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Continue with SAML SSO" })).toHaveAttribute("href", "/login/sso?next=%2Fdashboard");
  const main = page.getByRole("main");
  await expect(main.getByRole("link", { name: "Terms" })).toHaveAttribute("href", providers.termsUrl!);
  await expect(main.getByRole("link", { name: "Acceptable Use Policy" })).toHaveAttribute("href", providers.aupUrl!);
});

test("an emailed code signs in and returns to the requested page", async ({ page, api }) => {
  api.on("GET /auth/providers", only({ email: true }));
  api.on("POST /auth/email/start", undefined);
  api.on("POST /auth/email/verify", undefined);
  await page.goto("/login?next=/acme");
  await page.getByLabel("Email").fill(user.email!);
  await page.getByRole("button", { name: "Continue with email" }).click();
  await page.getByLabel("Verification code").fill("123456");
  await page.waitForURL("/acme");
  expect(api.sent("POST /auth/email/start")[0].body).toEqual({ email: user.email });
  expect(api.sent("POST /auth/email/verify")[0].body).toEqual({ email: user.email, code: "123456" });
});

test("SAML sign-in finds the organization by email domain", async ({ page, api }) => {
  api.on("GET /auth/sso/lookup", { org: "acme" });
  api.on("GET /auth/sso/acme/login", {});
  await page.goto("/login/sso?next=/acme");
  await page.getByLabel("Work email").fill(user.email!);
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page.waitForURL("/api/v1/auth/sso/acme/login?next=%2Facme");
  expect(api.sent("GET /auth/sso/lookup")[0].search).toBe("?email=ada%40example.com");
});

test("a passkey added on the account page signs in", async ({ page, api }) => {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  await cdp.send("WebAuthn.addVirtualAuthenticator", {
    options: { protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
  });
  api.on("POST /me/passkeys/options", {
    publicKey: {
      rp: { id: "localhost", name: "Liftgate" },
      user: { id: "dXNlci1hZGE", name: user.login, displayName: user.name },
      challenge: "cmVnaXN0ZXItY2hhbGxlbmdl",
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      authenticatorSelection: { residentKey: "required", userVerification: "preferred" },
    },
  });
  api.on("POST /me/passkeys", () => new Reply(201, { id: "passkey-laptop", name: "Laptop", createdAt: new Date().toISOString(), lastUsedAt: null }));
  await page.goto("/account?section=sign-in");
  await page.getByLabel("Passkey name").fill("Laptop");
  await page.getByRole("button", { name: "Add passkey" }).click();
  await expect.poll(() => api.sent("POST /me/passkeys").length).toBe(1);
  const created = (api.sent("POST /me/passkeys")[0].body as { credential: { id: string }; name: string }).credential.id;

  api.on("GET /auth/providers", only({ passkey: true }));
  api.on("POST /auth/passkey/verify", undefined);
  await page.addInitScript(() => (PublicKeyCredential.isConditionalMediationAvailable = async () => false));
  await page.goto("/login?next=/acme");
  await page.getByRole("button", { name: "Continue with passkey" }).click();
  await page.waitForURL("/acme");
  expect((api.sent("POST /auth/passkey/verify").at(-1)?.body as { credential: { id: string } }).credential.id).toBe(created);
});
