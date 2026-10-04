"use client";

import Link from "next/link";
import { useApi, useRole } from "@/lib/hooks";
import type { AuthProviders, ProjectTree } from "@/lib/types";
import { DatabaseCard } from "@/components/database-card";
import { EnvironmentCard } from "@/components/environment-card";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { buttonClasses } from "@/components/ui/button";
import { EmptyState, ErrorState } from "@/components/ui/empty-state";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";

export function Overview({ org, projectSlug }: { org: string; projectSlug: string }) {
  const { query: role, admin } = useRole(org);
  const tree = useApi<ProjectTree>(`/orgs/${org}/projects/${projectSlug}/tree`);
  const providers = useApi<AuthProviders>("/auth/providers").data;
  const project = tree.data?.project;
  const addService = admin && (
    <Link href={`/new?org=${org}&project=${projectSlug}`} className={buttonClasses("primary")}>
      Add service
    </Link>
  );
  if (tree.error?.status === 404) {
    return (
      <EmptyState
        title="Project not found"
        description={`There is no project called ${projectSlug} in ${org}.`}
        action={
          <Link href={`/${org}`} className={buttonClasses()}>
            Back to projects
          </Link>
        }
      />
    );
  }
  if (tree.error) return <ErrorState error={tree.error} retry={tree.reload} />;
  const href = `/${org}/${projectSlug}`;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title={project?.name ?? <Skeleton className="h-6 w-48" />}
        description={project && `${project.repoFullName} · ${project.repoDefaultBranch}`}
        actions={!!tree.data?.services.length && addService}
      />
      <Loaded query={tree} also={[role]} skeleton={<TableSkeleton />}>
        {({ environments, services }) => (
          <>
            {services.length === 0 && (
              <EmptyState
                title="No services yet"
                description={admin ? "Add a service to build and deploy this repository." : "An admin of this organization adds services."}
                action={addService}
              />
            )}
            {environments.map((environment) => {
              const inEnvironment = services.filter((s) => s.environmentId === environment.id);
              return (
                <EnvironmentCard key={environment.id} environment={environment} services={inEnvironment} href={`${href}/${environment.slug}`}>
                  <DatabaseCard environment={environment} services={inEnvironment} admin={admin} storage={providers?.storage} />
                </EnvironmentCard>
              );
            })}
          </>
        )}
      </Loaded>
    </div>
  );
}
