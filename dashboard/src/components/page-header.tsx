import type { ReactNode } from "react";

export function PageHeader({
  title,
  description,
  actions,
  centered = false,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  centered?: boolean;
}) {
  return (
    <div className={`flex flex-wrap items-start gap-4 ${centered ? "justify-center text-center" : "justify-between"}`}>
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-graphite-400">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
