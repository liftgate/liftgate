"use client";

import Link from "next/link";
import { useApi, useRole } from "@/lib/hooks";
import type { Project, Usage } from "@/lib/types";
import { DocsLink } from "@/components/docs-link";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { buttonClasses } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function Projects({ org }: { org: string }) {
  const { query: role, admin } = useRole(org);
  const projects = useApi<Project[]>(`/orgs/${org}/projects`);
  const importRepository = admin && (
    <Link href={`/new?org=${org}`} className={buttonClasses("primary")}>
      Import repository
    </Link>
  );
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Projects"
        description="Each project tracks one GitHub repository."
        actions={
          <>
            <Link href={`/${org}/settings`} className={buttonClasses("ghost")}>
              Settings
            </Link>
            {!!projects.data?.length && importRepository}
          </>
        }
      />
      <Loaded query={projects} also={[role]} skeleton={<TableSkeleton />}>
        {(list) =>
          list.length === 0 ? (
            <EmptyState
              title="No projects yet"
              description={
                <>
                  {admin ? "Import a GitHub repository to start deploying." : "An admin of this organization imports repositories."}{" "}
                  <DocsLink page="getting-started">Getting started</DocsLink>
                </>
              }
              action={importRepository}
            />
          ) : (
            <Table columns={["Name", "Repository", "Default branch"]}>
              {list.map((project) => (
                <Row key={project.id}>
                  <Cell>
                    <Link href={`/${org}/${project.slug}`} className="font-medium hover:text-accent">
                      {project.name}
                    </Link>
                  </Cell>
                  <Cell mono>{project.repoFullName}</Cell>
                  <Cell mono>{project.repoDefaultBranch}</Cell>
                </Row>
              ))}
            </Table>
          )
        }
      </Loaded>
      <UsageCard org={org} />
    </div>
  );
}

function UsageCard({ org }: { org: string }) {
  const usage = useApi<Usage>(`/orgs/${org}/usage`);
  return (
    <Card>
      <CardHeader
        title="Usage"
        description={
          <>
            What this organization uses against its plan. <DocsLink page="plans-and-limits">Plans and limits</DocsLink>
          </>
        }
        actions={usage.data && <Badge>{usage.data.plan} plan</Badge>}
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
