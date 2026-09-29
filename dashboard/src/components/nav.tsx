"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { useParams, usePathname, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { Organization, User } from "@/lib/types";
import { CreateOrgForm } from "./create-org-form";
import { Mark } from "./mark";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { Select } from "./ui/select";

export function Nav({ actions }: { actions?: ReactNode }) {
  const router = useRouter();
  const onLogin = usePathname().startsWith("/login");
  const { org, project, environment, service } = useParams<{ org?: string; project?: string; environment?: string; service?: string }>();
  const inApp = !onLogin && !actions;
  const me = useApi<User>(inApp && "/me");
  const orgs = useApi<Organization[]>(inApp && "/orgs");
  const [creating, setCreating] = useState(false);
  const signOut = useAction(async () => {
    await api("/auth/logout", { method: "POST" });
    window.location.replace("/login");
  });
  const suspended = orgs.data?.find((o) => o.slug === org && o.suspendedAt);
  const segments = [org, project, service && `${environment}/${service}`].filter((s): s is string => !!s);
  const crumbs = segments.map((label, i) => ({ label, href: `/${segments.slice(0, i + 1).join("/")}` }));
  return (
    <header className="border-b border-graphite-700 bg-graphite-900">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-6 px-6">
        <nav className="flex min-w-0 items-center gap-2 text-sm">
          <Link href={inApp ? "/dashboard" : "/"} className="flex items-center gap-2 font-semibold">
            <Mark />
            Liftgate
          </Link>
          {crumbs.map((crumb) => (
            <span key={crumb.href} className="flex min-w-0 items-center gap-2">
              <span className="text-graphite-600">/</span>
              <Link href={crumb.href} className="truncate text-graphite-200 hover:text-white">
                {crumb.label}
              </Link>
            </span>
          ))}
        </nav>
        {!onLogin && (
          <div className="flex items-center gap-4">
            {org && orgs.data && (
              <Select
                aria-label="Organization"
                value={org}
                onChange={(e) => (e.target.value ? router.push(`/${e.target.value}`) : setCreating(true))}
                className="max-w-40"
              >
                {orgs.data.map((o) => (
                  <option key={o.slug} value={o.slug}>
                    {o.name}
                  </option>
                ))}
                <option value="">New organization…</option>
              </Select>
            )}
            {me.data && (
              <Link href="/account" className="text-sm text-graphite-400 hover:text-white">
                {me.data.login}
              </Link>
            )}
            {actions ?? (
              <Button variant="ghost" pending={signOut.pending} onClick={() => signOut.run()}>
                Sign out
              </Button>
            )}
          </div>
        )}
      </div>
      {suspended && (
        <div role="status" className="border-t border-danger/40 bg-danger/10">
          <p className="mx-auto max-w-6xl px-6 py-2 text-sm text-danger">
            {suspended.name} is suspended{suspended.suspendedReason && ` for ${suspended.suspendedReason}`}. Its apps are stopped and changes are
            blocked until the operator lifts the suspension.
          </p>
        </div>
      )}
      <Dialog open={creating} title="New organization" onClose={() => setCreating(false)}>
        <CreateOrgForm
          onCreated={() => {
            setCreating(false);
            orgs.reload();
          }}
          onCancel={() => setCreating(false)}
        />
      </Dialog>
    </header>
  );
}
