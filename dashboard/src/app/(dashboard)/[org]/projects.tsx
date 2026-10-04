"use client";

import Link from "next/link";
import { useApi, useRole } from "@/lib/hooks";
import type { Project } from "@/lib/types";
import { DocsLink } from "@/components/docs-link";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { buttonClasses } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { TableSkeleton } from "@/components/ui/skeleton";
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
        actions={!!projects.data?.length && importRepository}
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
    </div>
  );
}
