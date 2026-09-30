"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { GitHubRepository, Project, Usage } from "@/lib/types";
import { formValues } from "@/lib/util";
import { DocsLink } from "@/components/docs-link";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { RepoPicker } from "@/components/repo-picker";
import { Badge } from "@/components/ui/badge";
import { Button, buttonClasses } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function Projects({ org, opening }: { org: string; opening: boolean }) {
  const router = useRouter();
  const { query: role, admin } = useRole(org);
  const [creating, setCreating] = useState(opening);
  const [repo, setRepo] = useState<GitHubRepository>();
  const [picker, setPicker] = useState(0);
  const projects = useApi<Project[]>(`/orgs/${org}/projects`);
  const create = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    try {
      const project = await api<Project>(`/orgs/${org}/projects`, {
        method: "POST",
        body: { slug: v.slug, name: v.name, repoFullName: v.repoFullName },
      });
      router.push(`/${org}/${project.slug}?new=service`);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === "github_not_connected")) throw e;
      setRepo(undefined);
      setPicker((n) => n + 1);
    }
  });
  const at = (field: string) => (create.field === field ? create.error : undefined);
  const close = () => {
    setCreating(false);
    setRepo(undefined);
    if (opening) window.history.replaceState(null, "", `/${org}`);
  };
  const newProject = admin && (
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
      <Loaded query={projects} also={[role]} skeleton={<TableSkeleton />}>
        {(list) =>
          list.length === 0 ? (
            <EmptyState
              title="No projects yet"
              description={
                <>
                  {admin ? "Connect a GitHub repository to start deploying." : "An admin of this organization connects repositories."}{" "}
                  <DocsLink page="getting-started">Getting started</DocsLink>
                </>
              }
              action={newProject}
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
      <Dialog open={admin && creating} title="New project" onClose={close}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.run(e.currentTarget);
          }}
          className="flex flex-col gap-4"
        >
          <RepoPicker key={picker} next={`/${org}?new=project`} value={repo?.fullName} error={at("repoFullName")} onChange={setRepo} />
          {repo && <NameSlugFields key={repo.fullName} prefill={repo.fullName.split("/")[1]} errorAt={at} />}
          <FormError message={create.field ? undefined : create.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={close}>Cancel</Button>
            <Button type="submit" variant="primary" pending={create.pending} disabled={!repo}>
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
