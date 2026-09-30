"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { PreviewStatus, ProjectTree, PullRequest } from "@/lib/types";
import { shortSha } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Field, FormError } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { PageSkeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

export function ProjectSettings({ org, projectSlug }: { org: string; projectSlug: string }) {
  const router = useRouter();
  const { query: role, admin } = useRole(org);
  const tree = useApi<ProjectTree>(`/orgs/${org}/projects/${projectSlug}/tree`);
  const id = tree.data?.project.id;
  const previews = useApi<PreviewStatus>(id && `/projects/${id}/previews`);
  const [status, setStatus] = useState<string>();
  const [deleting, setDeleting] = useState(false);
  const save = useAction(async (form: HTMLFormElement) => {
    setStatus(undefined);
    const data = new FormData(form);
    await api(`/projects/${id}`, {
      method: "PATCH",
      body: { previewsEnabled: data.has("previewsEnabled"), previewBaseEnvironmentId: data.get("previewBaseEnvironmentId") || null },
    });
    setStatus("Saved.");
    tree.reload();
  });
  const approve = useAction(async (pull: PullRequest) => {
    await api(`/projects/${id}/previews/approve`, { method: "POST", body: { number: pull.number, sha: pull.headSha } });
    previews.reload();
    tree.reload();
  });
  const remove = useAction(async () => {
    await api(`/projects/${id}`, { method: "DELETE" });
    router.push(`/${org}`);
  });
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Project settings" description="The repository, pull request previews and deletion of this project." />
      <Loaded query={tree} also={[role]} skeleton={<PageSkeleton />}>
        {({ project, environments }) => (
          <>
            <Card>
              <CardHeader title="Repository" description="Pushes to the branch an environment tracks build that environment's services." />
              <dl className="grid gap-4 p-6 text-sm sm:grid-cols-2">
                <div className="min-w-0">
                  <dt className="text-graphite-400">Repository</dt>
                  <dd className="mt-1 truncate font-mono">
                    <a href={`https://github.com/${project.repoFullName}`} target="_blank" rel="noreferrer" className="hover:text-accent">
                      {project.repoFullName}
                    </a>
                  </dd>
                </div>
                <div className="min-w-0">
                  <dt className="text-graphite-400">Default branch</dt>
                  <dd className="mt-1 truncate font-mono">{project.repoDefaultBranch}</dd>
                </div>
              </dl>
            </Card>
            <Card>
              <CardHeader
                title="Pull request previews"
                description="Each pull request gets its own environment with the base environment's services and variables. It is deleted when the pull request closes, or after 14 days without a push."
              />
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  save.run(e.currentTarget);
                }}
                className="flex flex-col gap-4 p-6"
              >
                {previews.data?.missing?.length ? (
                  <p className="rounded-md border border-warning/40 px-4 py-3 text-sm text-warning">
                    Previews need these GitHub App settings, which the installation on {project.repoFullName.split("/")[0]} does not have:{" "}
                    {previews.data.missing.join("; ")}. The App&apos;s owner adds them under Permissions and events, then the installation accepts them.
                  </p>
                ) : (
                  previews.data?.missing === null && <p className="text-sm text-graphite-400">Liftgate could not read the GitHub App&apos;s settings for this repository.</p>
                )}
                <label className="flex items-center gap-2 text-sm text-graphite-200">
                  <input type="checkbox" name="previewsEnabled" defaultChecked={project.previewsEnabled} disabled={!admin} className="accent-accent" />
                  Deploy a preview of every pull request
                </label>
                <Field label="Base environment" hint="Previews copy its services and variables">
                  <Select name="previewBaseEnvironmentId" defaultValue={project.previewBaseEnvironmentId ?? ""} disabled={!admin}>
                    <option value="">The production environment</option>
                    {environments
                      .filter((environment) => environment.pullRequest === null)
                      .map((environment) => (
                        <option key={environment.id} value={environment.id}>
                          {environment.name}
                        </option>
                      ))}
                  </Select>
                </Field>
                <FormError message={save.error} />
                {admin && (
                  <div className="flex items-center justify-end gap-2">
                    <span role="status" className="text-sm text-graphite-400">
                      {status}
                    </span>
                    <Button type="submit" variant="primary" pending={save.pending}>
                      Save
                    </Button>
                  </div>
                )}
              </form>
            </Card>
            <Card>
              <CardHeader
                title="Pull requests"
                description="A pull request from a fork waits until an admin approves its latest commit, because its build can read the base environment's variables."
              />
              <div className="flex flex-col gap-4 p-6">
                <Loaded query={previews} skeleton={<TableSkeleton rows={2} />}>
                  {({ pullRequests }) =>
                    pullRequests.length === 0 ? (
                      <EmptyState title="No pull requests yet" description={`Pull requests opened on ${project.repoFullName} while previews are on appear here.`} />
                    ) : (
                      <Table columns={["Pull request", "Commit", "Preview", ""]} label="Pull requests">
                        {pullRequests.map((pull) => {
                          const waiting = pull.fork && pull.approvedSha !== pull.headSha;
                          const preview = environments.find((environment) => environment.pullRequest === pull.number);
                          return (
                            <Row key={pull.number}>
                              <Cell>
                                <a
                                  href={`https://github.com/${project.repoFullName}/pull/${pull.number}`}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="font-medium hover:text-accent"
                                >
                                  #{pull.number} {pull.title}
                                </a>
                              </Cell>
                              <Cell mono>{shortSha(pull.headSha)}</Cell>
                              <Cell>
                                {pull.error ? (
                                  <span className="text-danger">{pull.error}</span>
                                ) : waiting ? (
                                  <Badge tone="warning">awaiting approval</Badge>
                                ) : preview ? (
                                  <Link href={`/${org}/${project.slug}`} className="font-mono text-xs hover:text-accent">
                                    {preview.slug}
                                  </Link>
                                ) : (
                                  <span className="text-graphite-400">None</span>
                                )}
                              </Cell>
                              <Cell className="text-right">
                                {waiting && admin && (
                                  <Button pending={approve.pending} onClick={() => approve.run(pull)}>
                                    Approve {shortSha(pull.headSha)}
                                  </Button>
                                )}
                              </Cell>
                            </Row>
                          );
                        })}
                      </Table>
                    )
                  }
                </Loaded>
                <FormError message={approve.error} />
              </div>
            </Card>
            {admin && (
              <Card>
                <CardHeader
                  title={<span className="text-danger">Delete project</span>}
                  description="Removes every environment of the project with its services, deployments, variables and domains. This cannot be undone."
                  actions={
                    <Button variant="danger" onClick={() => setDeleting(true)}>
                      Delete project
                    </Button>
                  }
                />
              </Card>
            )}
            <ConfirmDialog
              open={deleting}
              title="Delete project"
              typed={project.slug}
              pending={remove.pending}
              error={remove.error}
              onConfirm={() => remove.run()}
              onClose={() => setDeleting(false)}
            >
              <span className="font-medium text-white">{project.name}</span> is removed with every environment, service, deployment and variable.
            </ConfirmDialog>
          </>
        )}
      </Loaded>
    </div>
  );
}
