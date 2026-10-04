import { createServer } from "node:http";
import { build, expect, service, servicePath, settled, test } from "./fixtures";

test("pages and assets carry the security headers", async ({ request }) => {
  for (const path of ["/", "/login", "/acme", "/og.jpg"]) {
    const headers = (await request.get(path)).headers();
    expect(headers["content-security-policy"], path).toContain("frame-ancestors 'none'");
    expect(headers["content-security-policy"], path).toContain("base-uri 'self'");
    expect(headers["content-security-policy"], path).toContain("connect-src 'self'");
    expect(headers["x-content-type-options"], path).toBe("nosniff");
    expect(headers["referrer-policy"], path).toBe("strict-origin-when-cross-origin");
    expect(headers["strict-transport-security"], path).toBe("max-age=63072000");
    expect(headers["x-powered-by"], path).toBeUndefined();
  }
});

test("pages leave compression on, and the landing page is served gzipped", async ({ request }) => {
  const headers = { accept: "text/html", "accept-encoding": "gzip" };
  for (const path of ["/", "/login", "/acme"]) {
    expect((await request.get(path, { headers })).headers()["cache-control"], path).not.toContain("no-transform");
  }
  expect((await request.get("/", { headers })).headers()["content-encoding"]).toBe("gzip");
});

test("another site cannot frame the dashboard", async ({ page, baseURL }) => {
  const site = createServer((request, response) =>
    response.writeHead(200, { "content-type": "text/html" }).end(request.url === "/own" ? "own" : `<iframe src="/own"></iframe><iframe src="${baseURL}/login"></iframe>`),
  );
  await new Promise<void>((listening) => site.listen(3101, listening));
  try {
    await page.goto("http://localhost:3101/");
    await expect.poll(() => page.frames().map((frame) => frame.url())).toEqual(["http://localhost:3101/", "http://localhost:3101/own", "chrome-error://chromewebdata/"]);
  } finally {
    site.close();
  }
});

test("a same-origin socket passes the policy and a foreign one is refused", async ({ page, violations }) => {
  await page.goto("/login");
  await settled(page);
  const connect = (url: string) =>
    page.evaluate(
      (target) =>
        new Promise<void>((done) => {
          setTimeout(done, 2000);
          Object.assign(new WebSocket(target), { onerror: done, onclose: done });
        }),
      url,
    );
  await connect(`${new URL(page.url()).origin.replace("http", "ws")}/api/v1/probe`);
  expect(violations).toEqual([]);
  await connect("ws://foreign.example/api/v1/probe");
  await expect.poll(() => violations.join()).toContain("connect-src ws://foreign.example");
  violations.length = 0;
});

test("app pages are kept out of search results and carry a preview image, the landing page is indexable", async ({ request, baseURL }) => {
  const login = await (await request.get("/login")).text();
  expect(login).toContain('<meta name="robots" content="noindex, nofollow"/>');
  expect(login).toContain(`<meta property="og:image" content="${baseURL}/og.jpg"/>`);
  expect(await (await request.get("/")).text()).toContain('<meta name="robots" content="index, follow"/>');
});

test("a hidden tab makes no polling requests", async ({ page, api }) => {
  const builds = `GET /services/${service.id}/builds`;
  api.on(builds, [{ ...build, status: "running", finishedAt: null }]);
  await page.clock.install();
  await page.goto(`${servicePath}?tab=deployments`);
  await settled(page);
  const hide = (hidden: boolean) => page.evaluate((value) => Object.defineProperty(document, "hidden", { configurable: true, get: () => value }), hidden);
  await hide(true);
  const before = api.sent(builds).length;
  await page.clock.runFor(60_000);
  await page.waitForTimeout(1000);
  expect(api.sent(builds)).toHaveLength(before);
  await hide(false);
  await page.clock.runFor(5_000);
  await expect.poll(() => api.sent(builds).length).toBeGreaterThan(before);
});
