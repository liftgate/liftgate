"use client";

import { useApi } from "@/lib/hooks";
import { legalLinks } from "@/lib/legal";
import type { AuthProviders } from "@/lib/types";
import { linkTarget } from "@/lib/util";

export function Footer({ links: own = [], sidebar = false }: { links?: { label: string; href: string }[]; sidebar?: boolean }) {
  const links = [...own, ...legalLinks(useApi<AuthProviders>("/auth/providers").data)];
  if (links.length === 0) return null;
  const list = (
    <nav aria-label="Footer" className={`flex flex-wrap text-xs text-graphite-400 ${sidebar ? "gap-x-4 gap-y-2 px-2 pt-2" : "mx-auto max-w-6xl gap-4 px-6 py-4"}`}>
      {links.map((link) => (
        <a key={link.label} href={link.href} {...linkTarget(link.href)} className="hover:text-white">
          {link.label}
        </a>
      ))}
    </nav>
  );
  return sidebar ? list : <footer className="border-t border-graphite-700">{list}</footer>;
}
