import type { ReactNode } from "react";
import { docsUrl, type DocsPage } from "./landing/links";

export function DocsLink({ page, children }: { page: DocsPage; children: ReactNode }) {
  return (
    <a href={docsUrl(page)} target="_blank" rel="noreferrer" className="text-graphite-200 underline underline-offset-2 hover:text-white">
      {children}
    </a>
  );
}
