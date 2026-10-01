import { useEffect, useEffectEvent, useState, useTransition } from "react";
import { api, ApiError, describe } from "./api";
import type { Organization } from "./types";
import { pageUrl } from "./util";

export type Query<T> = { data?: T; error?: ApiError; loading: boolean; reload: () => void };

export function useApi<T>(key: string | null | undefined | false, load?: () => Promise<T>): Query<T> {
  const [state, setState] = useState<{ key: string; data?: T; error?: ApiError }>();
  const [tick, setTick] = useState(0);
  const run = useEffectEvent(() => (load ? load() : api<T>(key || "")));
  useEffect(() => {
    if (!key) return;
    let live = true;
    run().then(
      (data) => live && setState({ key, data }),
      (e: unknown) => live && setState({ key, error: e instanceof ApiError ? e : new ApiError(0, "unknown", describe(e)) }),
    );
    return () => {
      live = false;
    };
  }, [key, tick]);
  const current = state?.key === key ? state : undefined;
  return {
    data: current?.data,
    error: current?.error,
    loading: !!key && !current,
    reload: () => {
      setState((s) => (s?.error ? undefined : s));
      setTick((t) => t + 1);
    },
  };
}

export function useAction<A extends unknown[]>(fn: (...args: A) => Promise<void>) {
  const [pending, start] = useTransition();
  const [failure, setFailure] = useState<{ error: string; field?: string }>();
  const run = (...args: A) =>
    start(async () => {
      setFailure(undefined);
      try {
        await fn(...args);
      } catch (e) {
        setFailure({ error: describe(e), field: e instanceof ApiError ? e.field : undefined });
      }
    });
  return { pending, error: failure?.error, field: failure?.field, run, reset: () => setFailure(undefined) };
}

export function usePages<T>(path: string, cursor: (item: T) => string | number, size = 50) {
  const query = useApi<T[]>(pageUrl(path, size));
  const [older, setOlder] = useState<{ path: string; pages: T[][] }>();
  const pages = query.data ? [query.data, ...(older?.path === path ? older.pages : [])] : [];
  const last = pages.at(-1);
  const more = useAction(async () => {
    const tail = last?.at(-1);
    if (tail !== undefined) setOlder({ path, pages: [...pages.slice(1), await api<T[]>(pageUrl(path, size, cursor(tail)))] });
  });
  return { query, items: pages.flat(), more, hasMore: last?.length === size };
}

export function useCopy() {
  const [copied, setCopied] = useState(false);
  const copy = useAction(async (text: string) => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  });
  return { ...copy, copied };
}

export function usePolling(active: boolean, fn: () => void, ms = 5000) {
  const tick = useEffectEvent(fn);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => document.hidden || tick(), ms);
    return () => clearInterval(id);
  }, [active, ms]);
}

export const useSignOut = () =>
  useAction(async () => {
    await api("/auth/logout", { method: "POST" });
    window.location.replace("/login");
  });

export function useRole(org: string) {
  const query = useApi<Organization>(`/orgs/${org}`);
  const role = query.data?.role;
  return { query, admin: role === "owner" || role === "admin", owner: role === "owner" };
}
