"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { Project } from "@/lib/types";
import { formValues } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function Projects({ org }: { org: string }) {
  const router = useRouter();
  const [creating, setCreating] = useState(false);
  const projects = useApi<Project[]>(`/orgs/${org}/projects`);
  const create = useAction(async (form: HTMLFormElement) => {
    const v = formValues(form);
    const project = await api<Project>(`/orgs/${org}/projects`, {
      method: "POST",
      body: { slug: v.slug, name: v.name, repoFullName: v.repoFullName },
    });
    router.push(`/${org}/${project.slug}`);
  });
  const newProject = (
    <Button variant="primary" onClick={() => setCreating(true)}>
      New project
    </Button>
  );
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Projects" description="Each project tracks one GitHub repository." actions={newProject} />
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
      <Dialog open={creating} title="New project" onClose={() => setCreating(false)}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.run(e.currentTarget);
          }}
          className="flex flex-col gap-4"
        >
          <NameSlugFields />
          <Field label="GitHub repository" hint="owner/name, with the GitHub App installed and push access on your account">
            <Input name="repoFullName" required pattern="[^\/\s]+\/[^\/\s]+" placeholder="acme/web" className="font-mono" />
          </Field>
          <FormError message={create.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={() => setCreating(false)}>Cancel</Button>
            <Button type="submit" variant="primary" pending={create.pending}>
              Create project
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
