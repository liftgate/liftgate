import { headers } from "next/headers";
import { apiUrl } from "./api";

export async function apiStatus(path: string) {
  const cookie = (await headers()).get("cookie") ?? "";
  const api = process.env.LIFTGATE_API_URL;
  if (!api || apiUrl()) return undefined;
  return fetch(`${api}/api/v1${path}`, { headers: { cookie }, signal: AbortSignal.timeout(3000) })
    .then((res) => res.text().then(() => res.status))
    .catch(() => 0);
}
