import type { ReactNode } from "react";
import { Button } from "./button";

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-graphite-700 px-6 py-12 text-center">
      <p className="text-sm font-medium">{title}</p>
      {description && <p className="max-w-sm text-sm text-graphite-400">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, retry }: { error: Error; retry?: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-2 rounded-lg border border-danger/30 px-6 py-8 text-center">
      <p className="text-sm font-medium text-danger">Something went wrong</p>
      <p className="max-w-md text-sm text-graphite-200">{error.message}</p>
      {retry && (
        <Button className="mt-2" onClick={retry}>
          Try again
        </Button>
      )}
    </div>
  );
}
