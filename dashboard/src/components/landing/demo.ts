import type { Build, Deployment, Environment, EnvVar, Service, ServiceKind } from "@/lib/types";

export const environment: Environment = {
  id: "env",
  projectId: "project",
  slug: "production",
  name: "Production",
  kind: "production",
  branch: "main",
  namespace: "env-7c1e04b9a2d3",
};

const service = (id: string, kind: ServiceKind, rootDir: string, cpuMillis: number, memoryMb: number, cronSchedule: string | null = null, startCommand: string | null = null): Service => ({
  id,
  slug: id,
  name: id,
  environmentId: "env",
  kind,
  rootDir,
  buildStrategy: "auto",
  dockerfilePath: "Dockerfile",
  port: null,
  replicas: 1,
  cpuMillis,
  memoryMb,
  cronSchedule,
  startCommand,
});

export const services: Service[] = [
  service("web", "web", "/apps/web", 300, 256),
  service("api", "web", "/apps/api", 300, 256),
  service("worker", "worker", "/apps/api", 200, 256, null, "pnpm worker"),
  service("digest", "cron", "/apps/api", 100, 128, "0 6 * * *", "pnpm digest"),
  service("docs", "static", "/apps/docs", 100, 128),
];

export const envVars: EnvVar[] = [
  { name: "API_URL", value: "http://api", secret: false },
  { name: "LOG_LEVEL", value: "info", secret: false },
  { name: "SESSION_SECRET", value: null, secret: true },
  { name: "PAYMENTS_API_KEY", value: null, secret: true },
];

export const buildLog = [
  "╭─────────────────╮",
  "│ Railpack 0.39.0 │",
  "╰─────────────────╯",
  "",
  "  ↳ Detected Node",
  "  ↳ Using pnpm package manager",
  "",
  "  Packages",
  "  ──────────",
  "  node  │  22.23.2  │  package.json > engines > node (22)",
  "  pnpm  │  10.18.0  │  idiomatic-version-file (10.18.0)",
  "",
  "  Steps",
  "  ──────────",
  "  ▸ install",
  "    $ pnpm add -g node-gyp",
  "    $ pnpm install --frozen-lockfile --prefer-offline",
  "",
  "  ▸ build",
  "    $ pnpm run build",
  "",
  "  Deploy",
  "  ──────────",
  "    $ pnpm run start",
];

export function demo(now = Date.now()) {
  const ago = (seconds: number) => new Date(now - seconds * 1000).toISOString();
  const build = (id: string, status: Build["status"], commitSha: string, commitMessage: string, branch: string, seconds: number): Build => ({
    id,
    serviceId: "web",
    commitSha,
    commitMessage,
    branch,
    status,
    imageRef: null,
    error: null,
    startedAt: null,
    finishedAt: null,
    createdAt: ago(seconds),
    imagePruned: false,
  });
  const deployment = (id: string, status: Deployment["status"], buildId: string, seconds: number): Deployment => ({
    id,
    serviceId: "web",
    buildId,
    status,
    replicasReady: 1,
    error: null,
    createdAt: ago(seconds),
  });
  return {
    builds: [
      build("b1", "running", "4f2c9e1b7d03a58c6e21f94b0d7a3c85e1f6b209", "Show order history on the account page", "main", 40),
      build("b2", "succeeded", "b81d3a07e4c95f2d18a6b3e70c4d59f2a8e1b637", "Cache product images", "main", 3 * 3600),
      build("b3", "failed", "5d1b8f36a2e07c94b5d3f18e6a0c72b49d5e3f81", "Try Node 24", "main", 26 * 3600),
      build("b4", "succeeded", "9e07c55ad3b18f62e4a90c7d5b3e1f84a6c2d970", "Fix rounding in the cart total", "main", 50 * 3600),
    ],
    deployments: [
      deployment("d1", "running", "b1", 10),
      deployment("d2", "superseded", "b2", 3 * 3600),
      deployment("d3", "superseded", "b4", 50 * 3600),
    ],
  };
}
