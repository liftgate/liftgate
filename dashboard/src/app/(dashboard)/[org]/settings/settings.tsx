"use client";

import { api } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { Usage } from "@/lib/types";
import { planName } from "@/lib/util";
import { DangerZone } from "@/components/danger-zone";
import { DocsLink } from "@/components/docs-link";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader } from "@/components/ui/card";
import { CopyField } from "@/components/ui/copy-field";
import { Skeleton } from "@/components/ui/skeleton";

export function Settings({ org }: { org: string }) {
  const { query, owner } = useRole(org);
  const remove = useAction(async () => {
    await api(`/orgs/${org}`, { method: "DELETE" });
    window.location.replace("/dashboard");
  });
  return (
    <>
      <PageHeader title="General" />
      <Card>
        <CardHeader title="Organization" />
        <div className="grid grid-cols-1 gap-4 p-6 sm:grid-cols-2">
          <Loaded query={query} skeleton={<Skeleton className="h-14 sm:col-span-2" />}>
            {(organization) => (
              <>
                <dl className="flex flex-col gap-2 text-sm">
                  <dt className="font-medium text-graphite-200">Name</dt>
                  <dd className="flex h-8 items-center">{organization.name}</dd>
                </dl>
                <CopyField label="Slug" value={organization.slug} />
              </>
            )}
          </Loaded>
        </div>
      </Card>
      <UsageCard org={org} />
      {owner && (
        <DangerZone
          title="Delete organization"
          description="Removes every project with its services, deployments, variables and domains."
          typed={org}
          pending={remove.pending}
          error={remove.error}
          onConfirm={() => remove.run()}
        >
          <span className="font-medium text-white">{query.data?.name}</span> is removed with every project, service, deployment and variable. Members lose access.
        </DangerZone>
      )}
    </>
  );
}

function UsageCard({ org }: { org: string }) {
  const usage = useApi<Usage>(`/orgs/${org}/usage`);
  return (
    <Card>
      <CardHeader
        title="Plan and usage"
        description={
          <>
            What this organization uses against its plan. <DocsLink page="plans-and-limits">Plans and limits</DocsLink>
          </>
        }
        actions={usage.data && <Badge>{planName(usage.data.plan)} plan</Badge>}
      />
      <div className="p-6">
        <Loaded query={usage} skeleton={<UsageSkeleton />}>
          {(u) => (
            <dl className="grid grid-cols-2 gap-6 sm:grid-cols-3">
              {(
                [
                  ["Projects", u.projects, u.limits.projects, ""],
                  ["Services", u.services, u.limits.services, ""],
                  ["Custom domains", u.customDomains, u.limits.customDomains, ""],
                  ["Replicas", u.replicas, u.limits.replicas, ""],
                  ["CPU", u.cpuMillis, u.limits.cpuMillis, "m"],
                  ["Memory", u.memoryMb, u.limits.memoryMb, " MB"],
                  ["Storage", u.storageGb, u.limits.storageGb, " GB"],
                ] as const
              ).map(([label, used, limit, unit]) => (
                <div key={label} className="flex flex-col gap-1">
                  <dt className="text-sm text-graphite-400">{label}</dt>
                  <dd className="text-sm font-medium">
                    {used}
                    {unit} <span className="font-normal text-graphite-400">{limit === null ? "of unlimited" : `of ${limit}${unit}`}</span>
                  </dd>
                </div>
              ))}
            </dl>
          )}
        </Loaded>
      </div>
    </Card>
  );
}

function UsageSkeleton() {
  return (
    <div className="grid grid-cols-2 gap-6 sm:grid-cols-3">
      {Array.from({ length: 7 }, (_, i) => (
        <div key={i} className="flex flex-col gap-2">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 w-28" />
        </div>
      ))}
    </div>
  );
}
