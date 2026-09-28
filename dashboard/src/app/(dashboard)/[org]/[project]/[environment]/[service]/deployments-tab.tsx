"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Build, Deployment, Service } from "@/lib/types";
import { DeploymentTable } from "@/components/deployment-table";
import { Loaded } from "@/components/loaded";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";

const inProgress = (status: string) => ["pending", "releasing"].includes(status.toLowerCase());

export function DeploymentsTab({ service }: { service: Service }) {
  const deployments = useApi<Deployment[]>(`/services/${service.id}/deployments`);
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [target, setTarget] = useState<string>();
  const rollback = useAction(async (id: string) => {
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
            <DeploymentTable
              deployments={list}
              builds={builds.data}
              replicas={service.replicas}
              rolling={rollback.pending ? target : undefined}
              onRollback={(id) => {
                setTarget(id);
                rollback.run(id);
              }}
            />
          </div>
        )
      }
    </Loaded>
  );
}
