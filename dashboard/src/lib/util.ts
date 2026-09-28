import type { EnvVar, ProjectTree } from "./types";

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

export const slugify = (value: string) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");

export const formValues = (form: HTMLFormElement) =>
  Object.fromEntries([...new FormData(form)].map(([name, value]) => [name, String(value).trim()]));

export const safeNext = (value: unknown) => {
  const url = typeof value === "string" && value.startsWith("/") ? new URL(value, "http://next.invalid") : undefined;
  return url?.origin === "http://next.invalid" ? url.pathname + url.search + url.hash : "/";
};

const authErrors: Record<string, string> = {
  access_denied: "Sign-in was cancelled at the provider.",
  identity_in_use: "That account is already linked to another Liftgate user.",
  invalid_state: "The sign-in expired or was started in another tab. Try again.",
  invalid_saml: "Your identity provider's response was rejected. Ask an owner to check the SSO settings.",
  signup_closed: "Sign-up is closed on this Liftgate instance.",
  account_suspended: "This account is suspended.",
};

export const authError = (code: unknown) => (typeof code === "string" && code ? (authErrors[code] ?? "Sign-in failed. Try again.") : undefined);

type EnvRow = EnvVar & { stored?: boolean };

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
