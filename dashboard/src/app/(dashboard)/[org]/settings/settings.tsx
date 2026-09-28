"use client";

import Link from "next/link";
import { useRole } from "@/lib/hooks";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Card } from "@/components/ui/card";
import { TableSkeleton } from "@/components/ui/skeleton";

const sections = [
  { path: "members", title: "Members", description: "Who belongs to this organization and what each person can do.", role: "member" },
  { path: "audit", title: "Audit log", description: "Every change made here, who made it, and whether a token was used.", role: "admin" },
  { path: "tokens", title: "API tokens", description: "Credentials for CI and scripts, with an expiry and revocation.", role: "admin" },
  { path: "sso", title: "SAML single sign-on", description: "Members sign in through your identity provider.", role: "owner" },
  { path: "notifications", title: "Notifications", description: "Build failures and deployments posted to Slack, Discord or a signed webhook.", role: "admin" },
] as const;

export function Settings({ org }: { org: string }) {
  const { query, admin, owner } = useRole(org);
  const allowed = { member: true, admin, owner };
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Settings" description="Members, access, sign-in and notifications for this organization." />
      <Loaded query={query} skeleton={<TableSkeleton rows={2} />}>
        {() => (
          <Card className="overflow-hidden">
            <ul className="divide-y divide-graphite-700">
              {sections
                .filter((section) => allowed[section.role])
                .map((section) => (
                  <li key={section.path}>
                    <Link
                      href={`/${org}/settings/${section.path}`}
                      className="flex items-center justify-between gap-4 px-6 py-4 transition-colors hover:bg-graphite-800/50 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
                    >
                      <div className="min-w-0">
                        <p className="text-sm font-medium">{section.title}</p>
                        <p className="mt-1 text-sm text-graphite-400">{section.description}</p>
                      </div>
                      <span aria-hidden className="text-graphite-400">
                        →
                      </span>
                    </Link>
                  </li>
                ))}
            </ul>
          </Card>
        )}
      </Loaded>
    </div>
  );
}
