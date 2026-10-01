import type { Build, Deployment, EnvVar, ProjectTree, ServiceKind, Usage } from "./types";

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

export const platformHost = (labels: { service: string; environment: string; project: string; org: string }, deployDomain: string) => {
  const label = [labels.service === labels.project ? "" : labels.service, labels.environment === "production" ? "" : labels.environment, labels.project, labels.org].filter(Boolean).join("-");
  return label.length <= 63 ? `${label}.${deployDomain}` : undefined;
};

export const currentDeployment = <T extends { status: string }>(newestFirst: T[]) => newestFirst.find((d) => d.status === "running") ?? newestFirst[0];

export const canRollBack = (deployment: Deployment, build?: Build) => ["superseded", "rolled_back"].includes(deployment.status) && !!build && !build.imagePruned;

export const planName = (plan: string) => plan.charAt(0).toUpperCase() + plan.slice(1);

export const kindLabels: Record<ServiceKind, string> = { web: "Web service", static: "Static site", worker: "Worker", cron: "Cron job" };

const buildTimePrefixes = ["NEXT_PUBLIC_", "VITE_", "PUBLIC_", "NUXT_PUBLIC_", "REACT_APP_", "EXPO_PUBLIC_", "GATSBY_", "VUE_APP_"];
const secretWords = /(^|_)(SECRET|TOKEN|PASSWORD|PASSWD|PWD|PASS|PRIVATE|KEY|APIKEY|CREDENTIALS?|AUTH|SALT|SIGNING|DSN|WEBHOOK|COOKIE|SESSION)(_|$)/;
const secretNames = new Set(["DATABASE_URL", "REDIS_URL", "MONGODB_URI", "MONGO_URL", "SENTRY_DSN"]);

export const classifyVariable = (name: string, secretHint = false) => {
  const upper = name.toUpperCase();
  const buildTime = buildTimePrefixes.some((prefix) => upper.startsWith(prefix));
  return { buildTime, secret: !buildTime && (secretHint || secretWords.test(upper) || secretNames.has(upper) || upper.endsWith("_CONNECTION_STRING")) };
};

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
