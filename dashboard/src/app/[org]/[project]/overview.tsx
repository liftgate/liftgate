"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { Environment, Project, Service, ServiceSpec } from "@/lib/types";
import { formValues } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { ServiceForm } from "@/components/service-form";
import { StatusBadge } from "@/components/ui/badge";
import { Button, buttonClasses } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, ErrorState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function Overview({ org, projectSlug }: { org: string; projectSlug: string }) {
  const router = useRouter();
  const [dialog, setDialog] = useState<"environment" | "service">();
  const projects = useApi<Project[]>(`/orgs/${org}/projects`);
  const project = projects.data?.find((p) => p.slug === projectSlug);
  const environments = useApi<Environment[]>(project && `/projects/${project.id}/environments`);
  const createEnvironment = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    await api(`/projects/${project?.id}/environments`, {
      method: "POST",
      body: { slug: v.slug, name: v.name, kind: v.kind, branch: v.branch },
    });
    setDialog(undefined);
    environments.reload();
  });
  const createService = useAction(async (spec: ServiceSpec, values: Record<string, string>) => {
    const service = await api<Service>(`/environments/${values.environmentId}/services`, { method: "POST", body: spec });
    router.push(`/${org}/${projectSlug}/${service.slug}`);
  });
  if (projects.error) return <ErrorState error={projects.error} retry={projects.reload} />;
  if (projects.data && !project) {
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
  const href = `/${org}/${projectSlug}`;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title={project?.name ?? <Skeleton className="h-6 w-48" />}
        description={project && `${project.repoFullName} · ${project.repoDefaultBranch}`}
        actions={
          <>
            <Button onClick={() => setDialog("environment")} disabled={!project}>
              New environment
            </Button>
            <Button variant="primary" onClick={() => setDialog("service")} disabled={!environments.data?.length}>
              New service
            </Button>
          </>
        }
      />
      <Loaded query={environments} skeleton={<TableSkeleton />}>
        {(list) =>
          list.length === 0 ? (
            <EmptyState
              title="No environments yet"
              description="An environment tracks one branch of the repository and holds its services."
              action={<Button onClick={() => setDialog("environment")}>New environment</Button>}
            />
          ) : (
            list.map((environment) => <EnvironmentCard key={environment.id} environment={environment} href={href} />)
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
          <div className="grid grid-cols-2 gap-4">
            <Field label="Kind">
              <Select name="kind" defaultValue="production">
                <option value="production">production</option>
                <option value="preview">preview</option>
              </Select>
            </Field>
            <Field label="Branch" hint="Pushes to this branch trigger builds">
              <Input name="branch" required defaultValue={project?.repoDefaultBranch} className="font-mono" />
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
      <Dialog open={dialog === "service"} title="New service" onClose={() => setDialog(undefined)}>
        <ServiceForm
          before={
            <Field label="Environment">
              <Select name="environmentId" required>
                {environments.data?.map((environment) => (
                  <option key={environment.id} value={environment.id}>
                    {environment.name}
                  </option>
                ))}
              </Select>
            </Field>
          }
          pending={createService.pending}
          error={createService.error}
          submitLabel="Create service"
          onSubmit={createService.run}
          onCancel={() => setDialog(undefined)}
        />
      </Dialog>
    </div>
  );
}

function EnvironmentCard({ environment, href }: { environment: Environment; href: string }) {
  const services = useApi<Service[]>(`/environments/${environment.id}/services`);
  return (
    <Card>
      <CardHeader
        title={environment.name}
        description={`${environment.branch} · ${environment.namespace}`}
        actions={<StatusBadge status={environment.kind} />}
      />
      <div className="p-6">
        <Loaded query={services} skeleton={<TableSkeleton rows={2} />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState title="No services in this environment" description="Add a web, worker, cron or static service." />
            ) : (
              <Table columns={["Service", "Kind", "Resources", "Replicas"]}>
                {list.map((service) => (
                  <Row key={service.id}>
                    <Cell>
                      <Link href={`${href}/${service.slug}`} className="font-medium hover:text-accent">
                        {service.name}
                      </Link>
                    </Cell>
                    <Cell>
                      <StatusBadge status={service.kind} />
                    </Cell>
                    <Cell className="text-graphite-400">
                      {service.cpuMillis}m CPU · {service.memoryMb} MB
                    </Cell>
                    <Cell>{service.replicas}</Cell>
                  </Row>
                ))}
              </Table>
            )
          }
        </Loaded>
      </div>
    </Card>
  );
}
