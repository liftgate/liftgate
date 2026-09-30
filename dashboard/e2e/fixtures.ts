import { test as base, expect, type Page } from "@playwright/test";
import type {
  AuditEntry,
  AuthProviders,
  Backup,
  Build,
  Database,
  Deployment,
  Domain,
  EnvVar,
  Environment,
  ImportableRepositories,
  Member,
  MetricPoint,
  Organization,
  Project,
  ProjectTree,
  Service,
  ServiceMetrics,
  Usage,
  User,
} from "../src/lib/types";

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const sha = "4f2a9c1e7b3d5a6f8e9c0b1a2d3e4f5a6b7c8d9e";

export const user: User = { id: "user-ada", login: "ada", name: "Ada Lovelace", email: "ada@example.com", avatarUrl: null, status: "active" };
export const org: Organization = { id: "org-acme", slug: "acme", name: "Acme", plan: "free", suspendedAt: null, suspendedReason: null, role: "owner" };
export const project: Project = { id: "project-shop", orgId: org.id, slug: "shop", name: "Shop", repoFullName: "acme/shop", repoDefaultBranch: "main", installationId: 1, previewsEnabled: false, previewBaseEnvironmentId: null };
export const environment: Environment = {
  id: "environment-production",
  projectId: project.id,
  slug: "production",
  name: "Production",
  kind: "production",
  branch: "main",
  namespace: "env-acme-shop",
  pullRequest: null,
};
export const build: Build = {
  id: "build-1",
  serviceId: "service-web",
  commitSha: sha,
  commitMessage: "Add the checkout page",
  branch: "main",
  status: "succeeded",
  imageRef: "registry.example.com/acme/shop-production-web:4f2a9c1",
  error: null,
  startedAt: ago(62),
  finishedAt: ago(60),
  createdAt: ago(62),
  imagePruned: false,
};
export const deployment: Deployment = { id: "deployment-1", serviceId: build.serviceId, buildId: build.id, status: "running", replicasReady: 1, error: null, createdAt: ago(60), health: "healthy" };
export const service: Service = {
  id: build.serviceId,
  environmentId: environment.id,
  slug: "web",
  name: "Web",
  kind: "web",
  rootDir: "/",
  buildStrategy: "auto",
  dockerfilePath: "Dockerfile",
  port: null,
  replicas: 1,
  cpuMillis: 500,
  memoryMb: 512,
  cronSchedule: null,
  startCommand: null,
  healthCheckPath: "/healthz",
  watchPaths: [],
  volume: null,
  internalHost: "web.env-acme-shop.svc.cluster.local",
  url: "https://web-shop-acme.apps.example.com",
  current: { deploymentId: deployment.id, status: "running", replicasReady: 1, commitSha: sha, createdAt: deployment.createdAt },
};
export const servicePath = `/${org.slug}/${project.slug}/${environment.slug}/${service.slug}`;
export const database: Database = {
  id: "database-main",
  environmentId: environment.id,
  slug: "main",
  storageGb: 1,
  cpuMillis: 500,
  memoryMb: 512,
  restoredFrom: null,
  restoreTarget: null,
  createdAt: ago(120),
  links: [],
  ready: true,
};
export const backups: Backup[] = [{ name: "main-20260930", phase: "completed", startedAt: ago(119), stoppedAt: ago(118) }];
export const providers: AuthProviders = {
  oauth: ["github"],
  passkey: true,
  email: true,
  sso: true,
  customDomains: true,
  deployDomain: "apps.example.com",
  termsUrl: "https://example.com/terms",
  privacyUrl: "https://example.com/privacy",
  aupUrl: "https://example.com/acceptable-use",
};
export const platformDomain: Domain = {
  id: "domain-platform",
  serviceId: service.id,
  hostname: "web-shop-acme.apps.example.com",
  kind: "platform",
  verificationToken: null,
  verifiedAt: ago(60),
  certificateStatus: "ready",
  certificateMessage: null,
  dnsRecords: [],
};
const repositories: ImportableRepositories = {
  repositories: [
    { fullName: "acme/shop", defaultBranch: "main", private: true },
    { fullName: "acme/docs", defaultBranch: "main", private: false },
  ],
  installUrl: "https://github.example.com/apps/liftgate/installations/new",
};
const storedEnv: EnvVar[] = [
  { name: "DATABASE_URL", value: null, secret: true },
  { name: "LOG_LEVEL", value: "info", secret: false },
];

const members: Member[] = [
  { user, role: "owner" },
  { user: { id: "user-grace", login: "grace", name: "Grace Hopper", email: "grace@example.com", avatarUrl: null, status: "active" }, role: "member" },
];
const audit: AuditEntry[] = [
  { id: 1, actor: user, viaToken: false, action: "POST /orgs/{slug}/projects", targetType: "projects", targetId: project.id, details: { name: "Shop" }, createdAt: ago(90) },
];
const limits: Usage["limits"] = {
  ownedOrgs: 1,
  projects: 3,
  environmentsPerProject: 2,
  services: 5,
  cpuMillis: 2000,
  memoryMb: 4096,
  replicas: 5,
  cpuRequestRatio: 0.25,
  ephemeralMb: 1024,
  customDomains: 2,
  concurrentBuilds: 1,
  buildsPerHour: 10,
  egressBandwidth: "10M",
  udp: false,
  storageGb: 5,
};
const end = Math.floor(Date.now() / 1000);
const series = (scale: number): MetricPoint[] => Array.from({ length: 60 }, (_, i) => ({ time: end - (59 - i) * 60, value: scale * (1.2 + Math.sin(i / 6)) }));
const metrics: ServiceMetrics = {
  start: end - 3600,
  end,
  step: 60,
  cpu: series(0.05),
  memory: series(90 * 1024 * 1024),
  memoryLimitBytes: 512 * 1024 * 1024,
  networkRx: series(2048),
  networkTx: series(1024),
  restarts: 0,
};

export class Reply {
  constructor(
    readonly status: number,
    readonly body?: unknown,
  ) {}
}

const failure = (status: number, error: string, message: string) => new Reply(status, { error, message });

type Handler = (body: unknown) => unknown;

export class Api {
  readonly calls: { method: string; path: string; search: string; body: unknown }[] = [];
  readonly unmocked: string[] = [];
  private readonly handlers = new Map<string, Handler>();

  constructor() {
    const tree: ProjectTree = { project, environments: [environment], services: [service] };
    const usage: Usage = { plan: "free", limits, projects: 1, services: 1, customDomains: 0, replicas: 1, cpuMillis: 500, memoryMb: 512, storageGb: 1 };
    const replies: Record<string, unknown> = {
      "GET /auth/providers": providers,
      "POST /auth/passkey/options": { publicKey: { challenge: "c2lnbi1pbi1jaGFsbGVuZ2U", rpId: "localhost", userVerification: "preferred" } },
      "GET /me": user,
      "GET /me/identities": [{ id: "identity-github", provider: "github", email: user.email, createdAt: ago(9000), lastUsedAt: ago(30) }],
      "GET /me/passkeys": [],
      "GET /me/connections": [{ provider: "github", accountLogin: "ada", connectedAt: ago(9000) }],
      "GET /me/github/repositories": repositories,
      "GET /invitations/welcome": { slug: org.slug, name: org.name, role: "member", invitedBy: user.login },
      "GET /orgs": [org],
      "GET /orgs/acme": org,
      "GET /orgs/acme/projects": [project],
      "GET /orgs/acme/usage": usage,
      "GET /orgs/acme/members": members,
      "GET /orgs/acme/audit": audit,
      "GET /orgs/acme/tokens": [],
      "GET /orgs/acme/notifications": [],
      "GET /orgs/acme/sso": failure(404, "not_found", "organization has no sso connection"),
      "GET /orgs/acme/sso/sp": { entityId: "https://liftgate.example.com/api/v1/auth/sso/acme/metadata", acsUrl: "https://liftgate.example.com/api/v1/auth/sso/acme/acs" },
      "GET /orgs/acme/projects/shop/tree": tree,
      [`GET /projects/${project.id}/previews`]: { missing: [], pullRequests: [] },
      [`GET /services/${service.id}/deployments`]: [deployment],
      [`GET /services/${service.id}/builds`]: [build],
      [`GET /services/${service.id}/env`]: storedEnv,
      [`GET /services/${service.id}/domains`]: [platformDomain],
      [`GET /services/${service.id}/metrics`]: metrics,
      [`GET /environments/${environment.id}/databases`]: [database],
      [`GET /databases/${database.id}/backups`]: backups,
    };
    for (const [key, reply] of Object.entries(replies)) this.on(key, reply);
  }

  on(key: string, reply: unknown) {
    this.handlers.set(key, typeof reply === "function" ? (reply as Handler) : () => reply);
  }

  sent(key: string) {
    return this.calls.filter((call) => `${call.method} ${call.path}` === key);
  }

  async attach(page: Page) {
    await page.route("**/api/v1/**", async (route) => {
      const request = route.request();
      const { pathname, search } = new URL(request.url());
      const path = pathname.replace("/api/v1", "");
      const key = `${request.method()} ${path}`;
      const body = request.postData() ? request.postDataJSON() : undefined;
      this.calls.push({ method: request.method(), path, search, body });
      const handler = this.handlers.get(key);
      if (!handler) this.unmocked.push(key);
      const result = handler ? handler(body) : failure(404, "not_found", `${key} is not mocked`);
      const reply = result instanceof Reply ? result : result === undefined ? new Reply(204) : new Reply(200, result);
      await route.fulfill({ status: reply.status, contentType: "application/json", body: reply.body === undefined ? "" : JSON.stringify(reply.body) });
    });
    await page.routeWebSocket(/\/api\/v1\/logs\//, (socket) => {
      socket.send("Listening on port 8080");
      socket.send("GET /healthz 200 in 2ms");
    });
  }
}

export const test = base.extend<{ api: Api; violations: string[] }>({
  violations: [
    async ({ page }, use) => {
      const violations: string[] = [];
      await page.exposeBinding("reportViolation", (_, violation: string) => violations.push(violation));
      await page.addInitScript(() =>
        document.addEventListener("securitypolicyviolation", (e) =>
          (window as unknown as { reportViolation: (v: string) => void }).reportViolation(`${e.violatedDirective} ${e.blockedURI}`),
        ),
      );
      await use(violations);
    },
    { auto: true },
  ],
  api: [
    async ({ page, violations }, use) => {
      const api = new Api();
      await api.attach(page);
      await use(api);
      expect(api.unmocked, "API calls without a mock").toEqual([]);
      expect(violations, "Content-Security-Policy violations").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

export const settled = (page: Page) => expect(page.locator(".animate-pulse")).toHaveCount(0);
