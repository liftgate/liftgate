"use client";

import { useParams, usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useRole } from "@/lib/hooks";
import { SettingsLayout } from "@/components/settings-layout";

const sections = [
  { id: "", label: "General", role: "member" },
  { id: "members", label: "Members", role: "member" },
  { id: "notifications", label: "Notifications", role: "admin" },
  { id: "tokens", label: "API tokens", role: "admin" },
  { id: "sso", label: "Single sign-on", role: "owner" },
  { id: "audit", label: "Audit log", role: "admin" },
] as const;

export default function OrgSettingsLayout({ children }: { children: ReactNode }) {
  const { org } = useParams<{ org: string }>();
  const pathname = usePathname();
  const { query, admin, owner } = useRole(org);
  const allowed = { member: true, admin, owner };
  const base = `/${org}/settings`;
  return (
    <SettingsLayout
      sections={query.data || query.error ? sections.filter((s) => allowed[s.role]).map((s) => ({ id: s.id, label: s.label, href: s.id ? `${base}/${s.id}` : base })) : null}
      current={pathname.slice(base.length + 1)}
    >
      {children}
    </SettingsLayout>
  );
}
