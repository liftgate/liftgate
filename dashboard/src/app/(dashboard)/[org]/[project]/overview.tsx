"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { AuthProviders, ProjectTree } from "@/lib/types";
import { formValues } from "@/lib/util";
import { DatabaseCard } from "@/components/database-card";
import { EnvironmentCard } from "@/components/environment-card";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { Button, buttonClasses } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, ErrorState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";

export function Overview({ org, projectSlug }: { org: string; projectSlug: string }) {
  const { query: role, admin } = useRole(org);
  const [dialog, setDialog] = useState(false);
  const tree = useApi<ProjectTree>(`/orgs/${org}/projects/${projectSlug}/tree`);
  const providers = useApi<AuthProviders>("/auth/providers").data;
  const project = tree.data?.project;
  const environments = tree.data?.environments;
  const addService = `/new?org=${org}&project=${projectSlug}`;
  const createEnvironment = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    await api(`/projects/${project?.id}/environments`, {
      method: "POST",
      body: { slug: v.slug, name: v.name, kind: v.kind, branch: v.branch },
    });
    setDialog(false);
    tree.reload();
  });
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
        actions={
          <>
            <Link href={`${href}/settings`} className={buttonClasses()}>
              Settings
            </Link>
            {admin && (
              <>
                <Button onClick={() => setDialog(true)} disabled={!project}>
                  New environment
                </Button>
                {!!environments?.length && (
                  <Link href={addService} className={buttonClasses("primary")}>
                    Add service
                  </Link>
                )}
              </>
            )}
          </>
        }
      />
      <Loaded query={tree} also={[role]} skeleton={<TableSkeleton />}>
        {({ environments: list, services }) =>
          list.length === 0 ? (
            <EmptyState
              title="No environments yet"
              description="An environment tracks one branch of the repository and holds its services."
              action={admin && <Button onClick={() => setDialog(true)}>New environment</Button>}
            />
          ) : (
            list.map((environment) => {
              const inEnvironment = services.filter((s) => s.environmentId === environment.id);
              return (
                <EnvironmentCard
                  key={environment.id}
                  environment={environment}
                  services={inEnvironment}
                  href={`${href}/${environment.slug}`}
                  addHref={admin ? `${addService}&environment=${environment.slug}` : undefined}
                >
                  <DatabaseCard environment={environment} services={inEnvironment} admin={admin} storage={providers?.storage} />
                </EnvironmentCard>
              );
            })
          )
        }
      </Loaded>
      <Dialog open={dialog} title="New environment" onClose={() => setDialog(false)}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            createEnvironment.run(e.currentTarget);
          }}
          className="flex flex-col gap-4"
        >
          <NameSlugFields compact errorAt={(field) => (createEnvironment.field === field ? createEnvironment.error : undefined)} />
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Kind">
              <Select name="kind" defaultValue="production">
                <option value="production">Production</option>
                <option value="preview">Preview</option>
              </Select>
            </Field>
            <Field label="Branch" hint="Pushes to this branch trigger builds">
              <Input name="branch" required pattern=".*\S.*" defaultValue={project?.repoDefaultBranch} className="font-mono" />
            </Field>
          </div>
          <FormError message={createEnvironment.field === "name" || createEnvironment.field === "slug" ? undefined : createEnvironment.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={() => setDialog(false)}>Cancel</Button>
            <Button type="submit" variant="primary" pending={createEnvironment.pending}>
              Create environment
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
