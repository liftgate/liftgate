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

export function BuildsTab({ service, admin, linked, onSelect }: { service: Service; admin: boolean; linked?: string; onSelect: (id?: string) => void }) {
  const builds = useApi<Build[]>(`/services/${service.id}/builds`);
  const [selected, setSelected] = useState<string | null | undefined>(linked);
  const select = (id: string | null) => {
    setSelected(id);
    onSelect(id ?? undefined);
  };
  const deploy = useAction(async (form: HTMLFormElement) => {
    const { ref } = formValues(form);
    const build = await api<Build | undefined>(`/services/${service.id}/deploy`, { method: "POST", body: ref ? { ref } : {} });
    form.reset();
    if (build?.id) select(build.id);
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
          admin && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                deploy.run(e.currentTarget);
              }}
              className="flex w-full gap-2"
            >
              <Input name="ref" placeholder="Branch or commit (optional)" className="min-w-0 flex-1 font-mono sm:w-64" />
              <Button type="submit" variant="primary" pending={deploy.pending}>
                Deploy
              </Button>
            </form>
          )
        }
      >
        <FormError message={deploy.error} />
        <Loaded query={builds} skeleton={<TableSkeleton />}>
          {(list) =>
            list.length === 0 ? (
              <EmptyState
                title="No builds yet"
                description={admin ? "Push to the tracked branch or deploy a ref above." : "A push to the tracked branch starts a build."}
              />
            ) : (
              <BuildTable builds={list} selected={selected} onToggle={(id) => select(id === selected ? null : id)} />
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
