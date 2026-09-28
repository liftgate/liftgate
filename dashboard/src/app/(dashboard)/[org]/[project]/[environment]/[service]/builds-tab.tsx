"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { Build, Service } from "@/lib/types";
import { duration, formValues, shortSha } from "@/lib/util";
import { BuildsCard, BuildTable } from "@/components/build-table";
import { Loaded } from "@/components/loaded";
import { LogViewer } from "@/components/log-viewer";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";

const inProgress = (status: string) => ["queued", "running"].includes(status.toLowerCase());

export function BuildsTab({ service }: { service: Service }) {
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [selected, setSelected] = useState<string | null>();
  const deploy = useAction(async (form: HTMLFormElement) => {
    const { ref } = formValues(form);
    const build = await api<Build | undefined>(`/services/${service.id}/deploy`, { method: "POST", body: ref ? { ref } : {} });
    form.reset();
    if (build?.id) setSelected(build.id);
    builds.reload();
  });
  usePolling(!!builds.data?.some((b) => inProgress(b.status)), builds.reload);
  const opening = builds.data?.find((b) => b.status === "running" || b.status === "failed");
  if (selected === undefined && opening) setSelected(opening.id);
  const current = builds.data?.find((b) => b.id === selected);
  return (
    <div className="flex flex-col gap-6">
      <BuildsCard
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
      >
        <FormError message={deploy.error} />
        <Loaded query={builds} skeleton={<TableSkeleton />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState title="No builds yet" description="Push to the tracked branch or deploy a ref above." />
            ) : (
              <BuildTable builds={list} selected={selected} onToggle={(id) => setSelected(id === selected ? null : id)} />
            )
          }
        </Loaded>
      </BuildsCard>
      {current && (
        <div className="flex flex-col gap-2">
          <LogViewer
            key={current.id}
            path={`/logs/builds/${current.id}`}
            title={`Build ${shortSha(current.commitSha)}`}
            detail={duration(current.startedAt, current.finishedAt)}
          />
          {current.error && <p className="text-sm text-danger">{current.error}</p>}
        </div>
      )}
    </div>
  );
}
