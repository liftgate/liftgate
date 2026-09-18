"use client";

import Link from "next/link";
import { useParams, usePathname, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { Organization, User } from "@/lib/types";
import { Mark } from "./mark";
import { Button } from "./ui/button";
import { Select } from "./ui/select";

export function Nav() {
  const router = useRouter();
  const onLogin = usePathname() === "/login";
  const { org, project, service } = useParams<{ org?: string; project?: string; service?: string }>();
  const me = useApi<User>(onLogin ? null : "/me");
  const orgs = useApi<Organization[]>(onLogin ? null : "/orgs");
  const signOut = useAction(async () => {
    await api("/auth/logout", { method: "POST" });
    window.location.replace("/login");
  });
  const segments = [org, project, service].filter((s): s is string => !!s);
  const crumbs = segments.map((label, i) => ({ label, href: `/${segments.slice(0, i + 1).join("/")}` }));
  return (
    <header className="border-b border-graphite-700 bg-graphite-900">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-6 px-6">
        <nav className="flex min-w-0 items-center gap-2 text-sm">
          <Link href="/" className="flex items-center gap-2 font-semibold">
            <Mark />
            Liftgate
          </Link>
          {crumbs.map((crumb) => (
            <span key={crumb.href} className="flex items-center gap-2">
              <span className="text-graphite-600">/</span>
              <Link href={crumb.href} className="truncate text-graphite-200 hover:text-white">
                {crumb.label}
              </Link>
            </span>
          ))}
        </nav>
        {!onLogin && (
          <div className="flex items-center gap-4">
            {org && orgs.data && orgs.data.length > 1 && (
              <Select aria-label="Organization" value={org} onChange={(e) => router.push(`/${e.target.value}`)}>
                {orgs.data.map((o) => (
                  <option key={o.slug} value={o.slug}>
                    {o.name}
                  </option>
                ))}
              </Select>
            )}
            {me.data && <span className="text-sm text-graphite-400">{me.data.login}</span>}
            <Button variant="ghost" pending={signOut.pending} onClick={() => signOut.run()}>
              Sign out
            </Button>
          </div>
        )}
      </div>
    </header>
  );
}
