"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Build, Deployment, Environment, Service } from "@/lib/types";
import { duration, history, shortSha } from "@/lib/util";
import { DeploymentTable } from "@/components/deployment-table";
import { Loaded } from "@/components/loaded";
import { LogViewer } from "@/components/log-viewer";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";

const inProgress = (status: string) => ["queued", "running", "pending", "releasing"].includes(status.toLowerCase());

export function DeploymentsTab({
  service,
  environment,
  admin,
  linked,
  onSelect,
  onChanged,
}: {
  service: Service;
  environment: Environment;
  admin: boolean;
  linked?: string;
  onSelect: (id?: string) => void;
  onChanged: () => void;
}) {
  const deployments = useApi<Deployment[]>(`/services/${service.id}/deployments`);
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [selected, setSelected] = useState<string | null | undefined>(linked);
  const [target, setTarget] = useState<string>();
  const rollback = useAction(async (id: string) => {
    await api(`/deployments/${id}/rollback`, { method: "POST" });
    deployments.reload();
  });
  usePolling(!!deployments.data?.some((d) => inProgress(d.status)) || !!builds.data?.some((b) => inProgress(b.status)), () => {
    deployments.reload();
    builds.reload();
    onChanged();
  });
  const opening = builds.data?.find((b) => b.status === "running" || b.status === "failed");
  if (selected === undefined && opening) setSelected(opening.id);
  const toggle = (id: string) => {
    const next = id === selected ? null : id;
    setSelected(next);
    onSelect(next ?? undefined);
  };
  return (
    <Loaded query={deployments} also={[builds]} skeleton={<TableSkeleton />}>
      {(list) => {
        const rows = history(builds.data ?? [], list);
        return rows.length === 0 ? (
          <EmptyState
            title="No deployments yet"
            description={admin ? `Push to ${environment.branch} or use Deploy.` : `A push to ${environment.branch} builds and deploys this service.`}
          />
        ) : (
          <div className="flex flex-col gap-4">
            <FormError message={rollback.error} />
            <DeploymentTable
              rows={rows}
              replicas={service.replicas}
              selected={selected ?? undefined}
              onToggle={toggle}
              log={(build) => <LogViewer key={build.id} path={`/logs/builds/${build.id}`} title={`Build ${shortSha(build.commitSha)}`} detail={duration(build.startedAt, build.finishedAt)} />}
              rolling={rollback.pending ? target : undefined}
              onRollback={(id) => {
                setTarget(id);
                rollback.run(id);
              }}
              readOnly={!admin}
            />
          </div>
        );
      }}
    </Loaded>
  );
}
