import { useEffect, useEffectEvent, useState, useTransition } from "react";
import { api, ApiError, describe } from "./api";

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
  const [error, setError] = useState<string>();
  const run = (...args: A) =>
    start(async () => {
      setError(undefined);
      try {
        await fn(...args);
      } catch (e) {
        setError(describe(e));
      }
    });
  return { pending, error, run };
}

export function usePolling(active: boolean, fn: () => void, ms = 5000) {
  const tick = useEffectEvent(fn);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(tick, ms);
    return () => clearInterval(id);
  }, [active, ms]);
}
