import Link from "next/link";
import type { ReactNode } from "react";
import { PageHeader } from "./page-header";
import { Skeleton } from "./ui/skeleton";

export type SettingsSection = { id: string; label: string; href: string };

export function SettingsLayout({
  title,
  description,
  sections,
  current,
  children,
}: {
  title?: ReactNode;
  description?: ReactNode;
  sections?: SettingsSection[] | null;
  current?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-8">
      {title && <PageHeader title={title} description={description} />}
      <div className="flex flex-col gap-8 md:flex-row">
        {sections === null ? (
          <Skeleton className="h-8 md:h-32 md:w-48" />
        ) : (
          sections &&
          sections.length > 1 && (
            <nav aria-label="Settings sections" className="-mx-6 overflow-x-auto px-6 md:mx-0 md:w-48 md:shrink-0 md:px-0">
              <ul className="flex gap-1 md:flex-col">
                {sections.map((section) => (
                  <li key={section.id}>
                    <Link
                      href={section.href}
                      aria-current={section.id === current ? "page" : undefined}
                      className={`flex h-8 items-center whitespace-nowrap rounded-md px-2 text-sm transition-colors focus-visible:outline-2 focus-visible:outline-accent ${
                        section.id === current ? "bg-graphite-800 font-medium text-white" : "text-graphite-400 hover:text-white"
                      }`}
                    >
                      {section.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          )
        )}
        <div className="flex min-w-0 max-w-3xl flex-1 flex-col gap-8">
          {children}
        </div>
      </div>
    </div>
  );
}
