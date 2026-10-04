import type { Build, CurrentDeployment, Deployment, DetectedVariable, EnvironmentKind, EnvVar, OrgRole, ProjectTree, ServiceKind, ServiceSpec, Usage } from "./types";

export function timeAgo(iso: string) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  if (seconds < 86400 * 14) return `${Math.floor(seconds / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

export const lastUsed = (iso: string | null) => (iso ? timeAgo(iso) : "Never");

export const shortSha = (sha: string) => sha.slice(0, 7);

export const duration = (from: string | null, to: string | null) => {
  if (!from) return undefined;
  const seconds = Math.max(0, Math.round(((to ? new Date(to).getTime() : Date.now()) - new Date(from).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor(seconds / 60) % 60}m`;
};

const ansi = /\u001b(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001b]*(?:\u0007|\u001b\\)|[ -/]*[0-~])/g;

export const stripAnsi = (line: string) => line.replace(ansi, "");

export const slugify = (value: string) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");

export const pageUrl = (path: string, size: number, before?: string | number) =>
  `${path}${path.includes("?") ? "&" : "?"}limit=${size}${before === undefined ? "" : `&before=${encodeURIComponent(before)}`}`;

export const formValues = (form: HTMLFormElement) =>
  Object.fromEntries([...new FormData(form)].map(([name, value]) => [name, String(value).trim()]));

export const safeNext = (value: unknown) => {
  const url = typeof value === "string" && value.startsWith("/") ? new URL(value, "http://next.invalid") : undefined;
  return url?.origin === "http://next.invalid" ? url.pathname + url.search + url.hash : "/dashboard";
};

export const linkTarget = (href: string, origin = globalThis.location?.origin ?? "http://site.invalid") =>
  URL.canParse(href, origin) && new URL(href, origin).origin === origin ? {} : { target: "_blank", rel: "noreferrer" };

const authErrors: Record<string, string> = {
  access_denied: "Sign-in was cancelled at the provider.",
  identity_in_use: "That account is already linked to another Liftgate user.",
  invalid_state: "The sign-in expired or was started in another tab. Try again.",
  invalid_saml: "Your identity provider's response was rejected. Ask an owner to check the SSO settings.",
  signup_closed: "Sign-up is closed on this Liftgate instance.",
  account_suspended: "This account is suspended.",
};

export const authError = (code: unknown) => (typeof code === "string" && code ? (authErrors[code] ?? "Sign-in failed. Try again.") : undefined);

export type EnvRow = EnvVar & { stored?: boolean; description?: string | null; required?: boolean; updated?: boolean };

export const storedRows = (vars: EnvVar[]): EnvRow[] => vars.map((r) => (r.secret ? { ...r, value: null, stored: true } : r));

export const keepsStoredValue = (r: EnvRow) => !!r.stored && r.secret && !r.value;

export const envPayload = (rows: EnvRow[]) =>
  rows.map((r) => {
    if (!r.secret && r.value === null) throw new Error(`Retype the value of ${r.name} before saving it as a plain variable.`);
    return { name: r.name, value: keepsStoredValue(r) ? null : r.value, secret: r.secret };
  });

export const findService = (tree: ProjectTree, environmentSlug: string, serviceSlug: string) => {
  const environment = tree.environments.find((e) => e.slug === environmentSlug);
  const service = tree.services.find((s) => s.environmentId === environment?.id && s.slug === serviceSlug);
  return environment && service && { environment, service };
};

export const servesHttp = (kind: ServiceKind) => kind === "web" || kind === "static";

const trimSlashes = (path: string) => path.replace(/^\/+|\/+$/g, "");

type Placement = Pick<ServiceSpec, "rootDir" | "buildCommand"> & { dockerfilePath?: string };

export const sameApp = (a: Placement, b: Placement) =>
  trimSlashes(a.rootDir) === trimSlashes(b.rootDir) && (a.buildCommand ?? null) === (b.buildCommand ?? null) && (a.dockerfilePath ?? "Dockerfile") === (b.dockerfilePath ?? "Dockerfile");

export const platformHost = (labels: { service: string; environment: string; project: string; org: string }, deployDomain: string) => {
  const label = [labels.service === labels.project ? "" : labels.service, labels.environment === "production" ? "" : labels.environment, labels.project, labels.org].filter(Boolean).join("-");
  return label.length <= 63 ? `${label}.${deployDomain}` : undefined;
};

export const currentDeployment = <T extends { status: string }>(newestFirst: T[]) => newestFirst.find((d) => d.status === "running") ?? newestFirst[0];

export const building = (build: Build) => build.status === "queued" || build.status === "running";

export const releasing = (deployment: Deployment) => deployment.status === "pending" || deployment.status === "releasing";

export const canRollBack = (deployment: Deployment, build?: Build) => ["superseded", "rolled_back"].includes(deployment.status) && !!build && !build.imagePruned;

export const planName = (plan: string) => plan.charAt(0).toUpperCase() + plan.slice(1);

export const kindLabels: Record<ServiceKind, string> = { web: "Web service", static: "Static site", worker: "Worker", cron: "Cron job" };

export const environmentKindLabels: Record<EnvironmentKind, string> = { production: "Production", preview: "Preview" };

export const roleLabels: Record<OrgRole, string> = { owner: "Owner", admin: "Admin", member: "Member" };

const buildTimePrefixes = ["NEXT_PUBLIC_", "VITE_", "PUBLIC_", "NUXT_PUBLIC_", "REACT_APP_", "EXPO_PUBLIC_", "GATSBY_", "VUE_APP_"];
const secretWords = /(^|_)(SECRET|TOKEN|PASSWORD|PASSWD|PWD|PASS|PRIVATE|KEY|APIKEY|CREDENTIALS?|AUTH|SALT|SIGNING|DSN|WEBHOOK|COOKIE|SESSION)(_|$)/;
const secretNames = new Set(["DATABASE_URL", "REDIS_URL", "MONGODB_URI", "MONGO_URL", "SENTRY_DSN"]);

export const classifyVariable = (name: string, secretHint = false) => {
  const upper = name.toUpperCase();
  const buildTime = buildTimePrefixes.some((prefix) => upper.startsWith(prefix));
  return { buildTime, secret: !buildTime && (secretHint || secretWords.test(upper) || secretNames.has(upper) || upper.endsWith("_CONNECTION_STRING")) };
};

export const detectedRow = (v: DetectedVariable): EnvRow => ({ name: v.name, value: "", secret: classifyVariable(v.name, v.secretHint).secret, description: v.description, required: v.required });

export const mergeDotenv = (rows: EnvRow[], vars: { name: string; value: string }[]) => {
  const next = [...rows];
  let added = 0;
  let updated = 0;
  for (const { name, value } of vars) {
    const i = next.findIndex((row) => row.name === name);
    if (i < 0) {
      next.push({ name, value, secret: classifyVariable(name).secret });
      added++;
    } else if (next[i].value !== value) {
      next[i] = { ...next[i], value, updated: true };
      updated++;
    }
  }
  return { rows: next, added, updated };
};

export const pasteSummary = (added: number, updated: number, skipped: number[]) =>
  `Added ${added}, updated ${updated}, skipped ${skipped.length}${skipped.length ? ` (line${skipped.length > 1 ? "s" : ""} ${skipped.join(", ")})` : ""}.`;

export const fitPlan = (wanted: number, usage: Usage) => {
  const left = (limit: number | null, used: number) => (limit === null ? Infinity : Math.max(0, limit - used));
  const share = (total: number, count: number, step: number, most: number) => Math.min(most, Math.floor(total / count / step) * step);
  const cpu = left(usage.limits.cpuMillis, usage.cpuMillis);
  const memory = left(usage.limits.memoryMb, usage.memoryMb);
  const room = Math.min(left(usage.limits.services, usage.services), left(usage.limits.replicas, usage.replicas));
  for (let count = Math.min(wanted, room); count > 0; count--) {
    const fitted = { count, cpuMillis: share(cpu, count, 250, 500), memoryMb: share(memory, count, 256, 512) };
    if (fitted.cpuMillis >= 250 && fitted.memoryMb >= 256) return fitted;
  }
  return { count: 0, cpuMillis: 500, memoryMb: 512 };
};

export type HistoryRow = { key: string; build?: Build; deployment?: Deployment };

const newest = (row: HistoryRow) => Date.parse(row.deployment?.createdAt ?? row.build?.createdAt ?? "");

export const history = (builds: Build[], deployments: Deployment[]): HistoryRow[] =>
  [
    ...deployments.map((deployment) => ({ key: deployment.id, deployment, build: builds.find((b) => b.id === deployment.buildId) })),
    ...builds.filter((build) => !deployments.some((d) => d.buildId === build.id)).map((build) => ({ key: build.id, build })),
  ].sort((a, b) => newest(b) - newest(a));

export type Apply = "rebuild" | "redeploy";

type Applied = Partial<ServiceSpec> & { env?: EnvVar[] };

const rebuildFields = ["rootDir", "buildCommand", "dockerfilePath", "buildStrategy", "startCommand"] as const;

const changedNames = (before: EnvVar[], after: EnvVar[]) =>
  [...new Set([...before, ...after].map((v) => v.name))].filter((name) => {
    const was = before.find((v) => v.name === name);
    const is = after.find((v) => v.name === name);
    return !was || !is || was.secret !== is.secret || (is.value !== null && is.value !== was.value);
  });

export const applyAction = (current: Pick<CurrentDeployment, "status"> | null | undefined, before: Applied, after: Applied): Apply | undefined => {
  if (!current) return undefined;
  const changed = (Object.keys(after) as (keyof Applied)[]).filter((key) => key !== "env" && JSON.stringify(before[key] ?? null) !== JSON.stringify(after[key] ?? null));
  const names = changedNames(before.env ?? [], after.env ?? []);
  if (changed.some((key) => (rebuildFields as readonly string[]).includes(key)) || names.some((name) => classifyVariable(name).buildTime)) return "rebuild";
  return (changed.length || names.length) && ["pending", "releasing", "running"].includes(current.status) ? "redeploy" : undefined;
};
