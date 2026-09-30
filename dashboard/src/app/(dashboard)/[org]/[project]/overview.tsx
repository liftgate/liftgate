"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { AuthProviders, ProjectTree, Service, ServiceSpec } from "@/lib/types";
import { formValues, platformHost } from "@/lib/util";
import { DatabaseCard } from "@/components/database-card";
import { EnvironmentCard } from "@/components/environment-card";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { ServiceForm } from "@/components/service-form";
import { Button, buttonClasses } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, ErrorState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";

export function Overview({ org, projectSlug, onboarding }: { org: string; projectSlug: string; onboarding: boolean }) {
  const router = useRouter();
  const { query: role, admin } = useRole(org);
  const [dialog, setDialog] = useState<"environment" | "service" | "deploy" | undefined>(onboarding ? "deploy" : undefined);
  const [environmentId, setEnvironmentId] = useState<string>();
  const tree = useApi<ProjectTree>(`/orgs/${org}/projects/${projectSlug}/tree`);
  const deployDomain = useApi<AuthProviders>("/auth/providers").data?.deployDomain;
  const project = tree.data?.project;
  const environments = tree.data?.environments;
  const environment = environments?.find((e) => e.id === environmentId) ?? environments?.[0];
  const createEnvironment = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    await api(`/projects/${project?.id}/environments`, {
      method: "POST",
      body: { slug: v.slug, name: v.name, kind: v.kind, branch: v.branch },
    });
    setDialog(undefined);
    tree.reload();
  });
  const createService = useAction(async (spec: ServiceSpec) => {
    const service = await api<Service & { buildId?: string }>(`/environments/${environment?.id}/services${dialog === "deploy" ? "?deploy=true" : ""}`, { method: "POST", body: spec });
    router.push(`/${org}/${projectSlug}/${environment?.slug}/${service.slug}${service.buildId ? `?tab=builds&build=${service.buildId}` : ""}`);
  });
  const newService = (id?: string) => {
    setEnvironmentId(id);
    setDialog("service");
  };
  const close = () => {
    setDialog(undefined);
    if (onboarding) window.history.replaceState(null, "", `/${org}/${projectSlug}`);
  };
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
                <Button onClick={() => setDialog("environment")} disabled={!project}>
                  New environment
                </Button>
                <Button variant="primary" onClick={() => newService()} disabled={!environments?.length}>
                  New service
                </Button>
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
              action={admin && <Button onClick={() => setDialog("environment")}>New environment</Button>}
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
                  onNewService={admin ? () => newService(environment.id) : undefined}
                >
                  <DatabaseCard environment={environment} services={inEnvironment} admin={admin} />
                </EnvironmentCard>
              );
            })
          )
        }
      </Loaded>
      <Dialog open={dialog === "environment"} title="New environment" onClose={() => setDialog(undefined)}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            createEnvironment.run(e.currentTarget);
          }}
          className="flex flex-col gap-4"
        >
          <NameSlugFields />
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Kind">
              <Select name="kind" defaultValue="production">
                <option value="production">production</option>
                <option value="preview">preview</option>
              </Select>
            </Field>
            <Field label="Branch" hint="Pushes to this branch trigger builds">
              <Input name="branch" required pattern=".*\S.*" defaultValue={project?.repoDefaultBranch} className="font-mono" />
            </Field>
          </div>
          <FormError message={createEnvironment.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={() => setDialog(undefined)}>Cancel</Button>
            <Button type="submit" variant="primary" pending={createEnvironment.pending}>
              Create environment
            </Button>
          </div>
        </form>
      </Dialog>
      <Dialog open={admin && (dialog === "service" || dialog === "deploy") && !!environment} title={dialog === "deploy" ? "Configure and deploy" : "New service"} onClose={close}>
        <ServiceForm
          before={
            environments && environments.length > 1 && (
              <Field label="Environment">
                <Select value={environment?.id} onChange={(e) => setEnvironmentId(e.target.value)}>
                  {environments.map((environment) => (
                    <option key={environment.id} value={environment.id}>
                      {environment.name}
                    </option>
                  ))}
                </Select>
              </Field>
            )
          }
          prefill={dialog === "deploy" ? project?.name : undefined}
          hostFor={environment && deployDomain ? (slug) => platformHost({ service: slug, environment: environment.slug, project: projectSlug, org }, deployDomain) : undefined}
          pending={createService.pending}
          error={createService.error}
          errorField={createService.field}
          submitLabel={dialog === "deploy" ? "Deploy" : "Create service"}
          onSubmit={createService.run}
          onCancel={close}
        />
      </Dialog>
    </div>
  );
}
