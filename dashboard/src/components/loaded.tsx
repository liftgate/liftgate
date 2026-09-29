import type { ReactNode } from "react";
import type { Query } from "@/lib/hooks";
import { ErrorState } from "./ui/empty-state";

export function Loaded<T>({ query, also = [], skeleton, children }: { query: Query<T>; also?: Query<unknown>[]; skeleton: ReactNode; children: (data: T) => ReactNode }) {
  const failed = [query, ...also].find((q) => q.error);
  if (failed?.error) return <ErrorState error={failed.error} retry={failed.reload} />;
  if (query.data === undefined || also.some((q) => q.loading)) return skeleton;
  return children(query.data);
}
