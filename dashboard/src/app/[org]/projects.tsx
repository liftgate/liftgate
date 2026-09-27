"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { GitConnection, Project, Usage } from "@/lib/types";
import { formValues } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { ProviderLink } from "@/components/provider";
import { Badge } from "@/components/ui/badge";
import { Button, buttonClasses } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function Projects({ org }: { org: string }) {
  const router = useRouter();
  const [creating, setCreating] = useState(false);
  const [githubLost, setGithubLost] = useState(false);
  const projects = useApi<Project[]>(`/orgs/${org}/projects`);
  const connections = useApi<GitConnection[]>(creating && "/me/connections");
  const needsGithub = githubLost || connections.data?.every((c) => c.provider !== "github");
  const create = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    try {
      const project = await api<Project>(`/orgs/${org}/projects`, {
        method: "POST",
        body: { slug: v.slug, name: v.name, repoFullName: v.repoFullName },
      });
      router.push(`/${org}/${project.slug}`);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === "github_not_connected")) throw e;
      setGithubLost(true);
    }
  });
  const close = () => {
    setCreating(false);
    setGithubLost(false);
  };
  const newProject = (
    <Button variant="primary" onClick={() => setCreating(true)}>
      New project
    </Button>
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
            {newProject}
          </>
        }
      />
      <Loaded query={projects} skeleton={<TableSkeleton />}>
        {(list) =>
          list.length === 0 ? (
            <EmptyState title="No projects yet" description="Connect a GitHub repository to start deploying." action={newProject} />
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
      <Dialog open={creating} title="New project" onClose={close}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.run(e.currentTarget);
          }}
          className="flex flex-col gap-4"
        >
          <NameSlugFields />
          {needsGithub ? (
            <EmptyState
              title="Connect GitHub to import a repository"
              description="Liftgate reads your repositories through your GitHub connection."
              action={
                <ProviderLink provider="github" intent="connect" next={`/${org}`}>
                  Connect GitHub
                </ProviderLink>
              }
            />
          ) : connections.loading ? (
            <Skeleton className="h-16" />
          ) : (
            <Field label="GitHub repository" hint="owner/name, with the GitHub App installed and push access on your account">
              <Input name="repoFullName" required pattern="[^\/\s]+\/[^\/\s]+" placeholder="acme/web" className="font-mono" />
            </Field>
          )}
          <FormError message={create.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={close}>Cancel</Button>
            <Button type="submit" variant="primary" pending={create.pending} disabled={needsGithub || connections.loading}>
              Create project
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}

function UsageCard({ org }: { org: string }) {
  const usage = useApi<Usage>(`/orgs/${org}/usage`);
  return (
    <Card>
      <CardHeader
        title="Usage"
        description="What this organization uses against its plan"
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
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex flex-col gap-2">
          <Skeleton className="h-4 w-20" />
          <Skeleton className="h-4 w-28" />
        </div>
      ))}
    </div>
  );
}
