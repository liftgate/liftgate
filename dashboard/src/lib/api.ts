export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export const apiUrl = () => process.env.NEXT_PUBLIC_API_URL ?? "";

export const apiHref = (path: string) => `${apiUrl()}/api/v1${path}`;

type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export async function api<T>(path: string, init: { method?: Method; body?: unknown } = {}): Promise<T> {
  let res: Response;
  try {
    res = await fetch(apiHref(path), {
      method: init.method ?? "GET",
      credentials: "include",
      headers: init.body === undefined ? undefined : { "content-type": "application/json" },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch {
    throw new ApiError(0, "unreachable", "The Liftgate API is unreachable. Check that the control plane is running.");
  }
  if (res.status === 401 && typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
    window.location.replace(`/login?next=${encodeURIComponent(window.location.pathname)}`);
  }
  const text = await res.text();
  if (!res.ok) throw toError(res.status, text);
  return (text ? JSON.parse(text) : undefined) as T;
}

function toError(status: number, text: string) {
  try {
    const body = JSON.parse(text) as { error?: string; message?: string };
    return new ApiError(status, body.error ?? "http_error", body.message ?? `Request failed with status ${status}`);
  } catch {
    return new ApiError(status, "http_error", `Request failed with status ${status}`);
  }
}

export const describe = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong");
