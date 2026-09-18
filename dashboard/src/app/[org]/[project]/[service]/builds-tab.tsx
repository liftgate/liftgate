"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Build, Service } from "@/lib/types";
import { formValues, shortSha, timeAgo } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { LogViewer } from "@/components/log-viewer";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const inProgress = (status: string) => ["queued", "running"].includes(status.toLowerCase());

export function BuildsTab({ service }: { service: Service }) {
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [selected, setSelected] = useState<string>();
  const deploy = useAction(async (form: HTMLFormElement) => {
    const { ref } = formValues(form);
    const build = await api<Build | undefined>(`/services/${service.id}/deploy`, { method: "POST", body: ref ? { ref } : {} });
    form.reset();
    if (build?.id) setSelected(build.id);
    builds.reload();
  });
  usePolling(!!builds.data?.some((b) => inProgress(b.status)), builds.reload);
  const current = builds.data?.find((b) => b.id === selected);
  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader
          title="Builds"
          description="Every push to the tracked branch queues a build. Start one by hand from a branch or commit."
          actions={
            <form
              onSubmit={(e) => {
                e.preventDefault();
                deploy.run(e.currentTarget);
              }}
              className="flex gap-2"
            >
              <Input name="ref" placeholder="Branch or commit (optional)" className="w-64 font-mono" />
              <Button type="submit" variant="primary" pending={deploy.pending}>
                Deploy
              </Button>
            </form>
          }
        />
        <div className="flex flex-col gap-4 p-6">
          <FormError message={deploy.error} />
          <Loaded query={builds} skeleton={<TableSkeleton />}>
            {(list) =>
              list.length === 0 ? (
                <EmptyState title="No builds yet" description="Push to the tracked branch or deploy a ref above." />
              ) : (
                <Table columns={["Status", "Commit", "Branch", "Created", ""]}>
                  {list.map((build) => (
                    <Row key={build.id} selected={build.id === selected}>
                      <Cell>
                        <StatusBadge status={build.status} />
                      </Cell>
                      <Cell mono>
                        {shortSha(build.commitSha)}
                        {build.commitMessage && (
                          <span className="ml-2 font-sans text-sm text-graphite-400">{build.commitMessage.split("\n")[0]}</span>
                        )}
                      </Cell>
                      <Cell mono>{build.branch}</Cell>
                      <Cell className="text-graphite-400">
                        <span title={build.createdAt}>{timeAgo(build.createdAt)}</span>
                      </Cell>
                      <Cell className="text-right">
                        <Button variant="ghost" onClick={() => setSelected(build.id === selected ? undefined : build.id)}>
                          {build.id === selected ? "Hide logs" : "Logs"}
                        </Button>
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )
            }
          </Loaded>
        </div>
      </Card>
      {current && (
        <div className="flex flex-col gap-2">
          <LogViewer key={current.id} path={`/logs/builds/${current.id}`} title={`Build ${shortSha(current.commitSha)}`} />
          {current.error && <p className="text-sm text-danger">{current.error}</p>}
          {!inProgress(current.status) && (
            <p className="text-xs text-graphite-400">Output streams while a build runs; this build has already finished.</p>
          )}
        </div>
      )}
    </div>
  );
}
