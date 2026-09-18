"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Build, Deployment, Service } from "@/lib/types";
import { shortSha, timeAgo } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const inProgress = (status: string) => ["pending", "releasing"].includes(status.toLowerCase());

export function DeploymentsTab({ service }: { service: Service }) {
  const deployments = useApi<Deployment[]>(`/services/${service.id}/deployments`);
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [target, setTarget] = useState<string>();
  const rollback = useAction(async (id: string) => {
    setTarget(id);
    await api(`/deployments/${id}/rollback`, { method: "POST" });
    deployments.reload();
  });
  usePolling(!!deployments.data?.some((d) => inProgress(d.status)), deployments.reload);
  return (
    <Loaded query={deployments} skeleton={<TableSkeleton />}>
      {(list) =>
        list.length === 0 ? (
          <EmptyState
            title="No deployments yet"
            description="Start a build from the Builds tab. A successful build is released automatically."
          />
        ) : (
          <div className="flex flex-col gap-4">
            <FormError message={rollback.error} />
            <Table columns={["Status", "Build", "Ready", "Created", ""]}>
              {[...list]
                .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
                .map((deployment, i) => {
                  const build = builds.data?.find((b) => b.id === deployment.buildId);
                  return (
                    <Row key={deployment.id}>
                      <Cell>
                        <StatusBadge status={deployment.status} />
                        {deployment.error && <p className="mt-1 max-w-xs text-xs text-danger">{deployment.error}</p>}
                      </Cell>
                      <Cell mono>
                        {build ? shortSha(build.commitSha) : deployment.buildId.slice(0, 8)}
                        {build?.commitMessage && <span className="ml-2 font-sans text-sm text-graphite-400">{build.commitMessage.split("\n")[0]}</span>}
                      </Cell>
                      <Cell>
                        {deployment.replicasReady}/{service.replicas}
                      </Cell>
                      <Cell className="text-graphite-400">
                        <span title={deployment.createdAt}>{timeAgo(deployment.createdAt)}</span>
                      </Cell>
                      <Cell className="text-right">
                        {i > 0 && (
                          <Button
                            disabled={rollback.pending}
                            pending={rollback.pending && target === deployment.id}
                            onClick={() => rollback.run(deployment.id)}
                          >
                            Roll back to this
                          </Button>
                        )}
                      </Cell>
                    </Row>
                  );
                })}
            </Table>
          </div>
        )
      }
    </Loaded>
  );
}
