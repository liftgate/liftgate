"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Build, Deployment, Service } from "@/lib/types";
import { DeploymentTable } from "@/components/deployment-table";
import { Loaded } from "@/components/loaded";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";

const inProgress = (status: string) => ["pending", "releasing"].includes(status.toLowerCase());

export function DeploymentsTab({ service, admin, onBuild, onChanged }: { service: Service; admin: boolean; onBuild: (id: string) => void; onChanged: () => void }) {
  const deployments = useApi<Deployment[]>(`/services/${service.id}/deployments`);
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [target, setTarget] = useState<string>();
  const rollback = useAction(async (id: string) => {
    await api(`/deployments/${id}/rollback`, { method: "POST" });
    deployments.reload();
  });
  const deploy = useAction(async () => onBuild((await api<Build>(`/services/${service.id}/deploy`, { method: "POST", body: {} })).id));
  usePolling(!!deployments.data?.some((d) => inProgress(d.status)), () => {
    deployments.reload();
    onChanged();
  });
  return (
    <Loaded query={deployments} skeleton={<TableSkeleton />}>
      {(list) =>
        list.length === 0 ? (
          <div className="flex flex-col gap-4">
            <EmptyState
              title="No deployments yet"
              description={admin ? "Build the head of the tracked branch. A successful build is released automatically." : "A successful build is released automatically."}
              action={
                admin && (
                  <Button variant="primary" pending={deploy.pending} onClick={() => deploy.run()}>
                    Deploy
                  </Button>
                )
              }
            />
            <FormError message={deploy.error} />
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            <FormError message={rollback.error} />
            <DeploymentTable
              deployments={list}
              builds={builds.data}
              replicas={service.replicas}
              rolling={rollback.pending ? target : undefined}
              onRollback={(id) => {
                setTarget(id);
                rollback.run(id);
              }}
              readOnly={!admin}
            />
          </div>
        )
      }
    </Loaded>
  );
}
