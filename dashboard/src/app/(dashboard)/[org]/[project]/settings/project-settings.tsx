"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, useRole } from "@/lib/hooks";
import type { Environment, PreviewStatus, ProjectTree, PullRequest } from "@/lib/types";
import { environmentKindLabels, formValues, shortSha } from "@/lib/util";
import { DangerZone } from "@/components/danger-zone";
import { Loaded } from "@/components/loaded";
import { NameSlugFields } from "@/components/name-slug-fields";
import { SettingsLayout } from "@/components/settings-layout";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { ConfirmDialog, Dialog } from "@/components/ui/dialog";
import { Field, FormError, Input } from "@/components/ui/input";
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
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<Environment>();
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
  const addEnvironment = useAction(async (form: HTMLFormElement) => {
    const { slug, name, branch } = formValues(form);
    await api(`/projects/${id}/environments`, { method: "POST", body: { slug, name, branch } });
    setAdding(false);
    tree.reload();
  });
  const removeEnvironment = useAction(async (environment: Environment) => {
    await api(`/environments/${environment.id}`, { method: "DELETE" });
    setDeleting(undefined);
    tree.reload();
  });
  const remove = useAction(async () => {
    await api(`/projects/${id}`, { method: "DELETE" });
    router.push(`/${org}`);
  });
  const project = tree.data?.project;
  return (
    <SettingsLayout
      title="Project settings"
      description={
        project && (
          <>
            <a href={`https://github.com/${project.repoFullName}`} target="_blank" rel="noreferrer" className="font-mono hover:text-white">
              {project.repoFullName}
            </a>{" "}
            · default branch <span className="font-mono">{project.repoDefaultBranch}</span>
          </>
        )
      }
    >
      <Loaded query={tree} also={[role]} skeleton={<PageSkeleton />}>
        {({ project, environments }) => {
          const production = environments.filter((environment) => environment.kind === "production").length;
          return (
            <>
              <Card>
                <CardHeader title="Environments" actions={admin && <Button onClick={() => setAdding(true)}>New environment</Button>} />
                <div className="p-6">
                  <Table columns={["Environment", "Branch", "Kind", ""]} label="Environments">
                    {environments.map((environment) => (
                      <Row key={environment.id}>
                        <Cell className="font-medium">{environment.name}</Cell>
                        <Cell mono>{environment.branch}</Cell>
                        <Cell>
                          <Badge>{environmentKindLabels[environment.kind]}</Badge>
                        </Cell>
                        <Cell className="text-right">
                          {admin && (environment.kind !== "production" || production > 1) && (
                            <Button variant="danger" onClick={() => setDeleting(environment)}>
                              Delete
                            </Button>
                          )}
                        </Cell>
                      </Row>
                    ))}
                  </Table>
                </div>
              </Card>
              <Card>
                <CardHeader title="Pull request previews" description="Each pull request gets its own environment, deleted when it closes or after 14 days without a push." />
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
                <div className="flex flex-col gap-4 border-t border-graphite-700 p-6">
                  <div>
                    <h3 className="text-sm font-medium">Pull requests</h3>
                    <p className="mt-1 text-sm text-graphite-400">Fork builds wait for an admin&apos;s approval because they can read the base environment&apos;s variables.</p>
                  </div>
                  <Loaded query={previews} skeleton={<TableSkeleton rows={2} />}>
                    {({ pullRequests }) =>
                      pullRequests.length === 0 ? (
                        <p className="text-sm text-graphite-400">Pull requests opened on {project.repoFullName} while previews are on appear here.</p>
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
                <DangerZone
                  title="Delete project"
                  description="Removes every environment with its services, deployments, variables and domains."
                  typed={project.slug}
                  pending={remove.pending}
                  error={remove.error}
                  onConfirm={() => remove.run()}
                >
                  <span className="font-medium text-white">{project.name}</span> is removed with every environment, service, deployment and variable.
                </DangerZone>
              )}
              <Dialog open={adding} title="New environment" onClose={() => setAdding(false)}>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    addEnvironment.run(e.currentTarget);
                  }}
                  className="flex flex-col gap-4"
                >
                  <NameSlugFields compact errorAt={(field) => (addEnvironment.field === field ? addEnvironment.error : undefined)} />
                  <Field label="Branch" hint="Pushes to this branch trigger builds">
                    <Input name="branch" required pattern=".*\S.*" defaultValue={project.repoDefaultBranch} className="font-mono" />
                  </Field>
                  <FormError message={addEnvironment.field === "name" || addEnvironment.field === "slug" ? undefined : addEnvironment.error} />
                  <div className="flex justify-end gap-2">
                    <Button onClick={() => setAdding(false)}>Cancel</Button>
                    <Button type="submit" variant="primary" pending={addEnvironment.pending}>
                      Create environment
                    </Button>
                  </div>
                </form>
              </Dialog>
              <ConfirmDialog
                open={!!deleting}
                title="Delete environment"
                typed={deleting?.slug}
                pending={removeEnvironment.pending}
                error={removeEnvironment.error}
                onConfirm={() => deleting && removeEnvironment.run(deleting)}
                onClose={() => setDeleting(undefined)}
              >
                <span className="font-medium text-white">{deleting?.name}</span> is removed with its services, deployments, variables and domains.
              </ConfirmDialog>
            </>
          );
        }}
      </Loaded>
    </SettingsLayout>
  );
}
