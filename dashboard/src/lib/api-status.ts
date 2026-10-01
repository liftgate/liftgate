import { headers } from "next/headers";

export async function apiStatus(path: string) {
  const cookie = (await headers()).get("cookie") ?? "";
  const api = process.env.LIFTGATE_API_URL;
  if (!api) return undefined;
  return fetch(`${api}/api/v1${path}`, { headers: { cookie }, signal: AbortSignal.timeout(3000) })
    .then((res) => res.text().then(() => res.status))
    .catch(() => 0);
}
