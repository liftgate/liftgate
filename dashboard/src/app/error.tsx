"use client";

import { Shell } from "@/components/shell";
import { ErrorState } from "@/components/ui/empty-state";

export default function ErrorPage({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <Shell>
      <ErrorState error={error} retry={retry} />
    </Shell>
  );
}
