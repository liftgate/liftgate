export function timeAgo(iso: string) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  if (seconds < 86400 * 14) return `${Math.floor(seconds / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

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
};

export const authError = (code: unknown) => (typeof code === "string" && code ? (authErrors[code] ?? "Sign-in failed. Try again.") : undefined);
