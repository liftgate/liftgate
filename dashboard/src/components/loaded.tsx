import type { ReactNode } from "react";
import type { Query } from "@/lib/hooks";
import { ErrorState } from "./ui/empty-state";

export function Loaded<T>({ query, skeleton, children }: { query: Query<T>; skeleton: ReactNode; children: (data: T) => ReactNode }) {
  if (query.error) return <ErrorState error={query.error} retry={query.reload} />;
  if (query.data === undefined) return skeleton;
  return children(query.data);
}
