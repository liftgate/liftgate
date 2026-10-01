import type { ReactNode } from "react";

export function Card({ className = "", children }: { className?: string; children: ReactNode }) {
  return <section className={`rounded-lg border border-graphite-700 bg-graphite-900 ${className}`}>{children}</section>;
}

export function CardHeader({ title, description, actions, divided = true }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; divided?: boolean }) {
  return (
    <div className={`flex items-start justify-between gap-4 px-6 py-4 max-md:flex-col ${divided ? "border-b border-graphite-700" : ""}`}>
      <div className="min-w-0">
        <h2 className="text-base font-medium">{title}</h2>
        {description && <p className="mt-1 text-sm text-graphite-400">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2 max-md:self-stretch">{actions}</div>}
    </div>
  );
}
