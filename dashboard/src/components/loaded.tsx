import type { ReactNode } from "react";
import type { Query } from "@/lib/hooks";
import { ErrorState } from "./ui/empty-state";

export function Loaded<T>({ query, also, skeleton, children }: { query: Query<T>; also?: Query<unknown>; skeleton: ReactNode; children: (data: T) => ReactNode }) {
  if (query.error) return <ErrorState error={query.error} retry={query.reload} />;
  if (also?.error) return <ErrorState error={also.error} retry={also.reload} />;
  if (query.data === undefined || also?.loading) return skeleton;
  return children(query.data);
}
