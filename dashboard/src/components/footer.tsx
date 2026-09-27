"use client";

import { useApi } from "@/lib/hooks";
import { legalLinks } from "@/lib/legal";
import type { AuthProviders } from "@/lib/types";

export function Footer() {
  const links = legalLinks(useApi<AuthProviders>("/auth/providers").data);
  if (links.length === 0) return null;
  return (
    <footer className="border-t border-graphite-700">
      <nav aria-label="Legal" className="mx-auto flex max-w-6xl flex-wrap gap-4 px-6 py-4 text-xs text-graphite-400">
        {links.map((link) => (
          <a key={link.label} href={link.href} target="_blank" rel="noreferrer" className="hover:text-white">
            {link.label}
          </a>
        ))}
      </nav>
    </footer>
  );
}
