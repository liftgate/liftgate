"use client";

import { useEffect, useEffectEvent, useState } from "react";
import { api } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import type { DetectedVariable, Detection, EnvVar } from "@/lib/types";
import { detectedRow, envPayload, sameApp, storedRows } from "@/lib/util";
import { EnvRows, useEnvPaste } from "./env-rows";
import { redeployRequested, SaveActions, saved } from "./save-actions";
import { Button } from "./ui/button";
import { Card, CardHeader } from "./ui/card";
import { EmptyState } from "./ui/empty-state";
import { FormError } from "./ui/input";

type Repository = { projectId: string; branch: string; rootDir: string; buildCommand?: string | null; dockerfilePath: string };

export function EnvEditor({ serviceId, initial, repository, databaseHref }: { serviceId: string; initial: EnvVar[]; repository?: Repository; databaseHref?: string }) {
  const [rows, setRows] = useState(() => storedRows(initial));
  const [status, setStatus] = useState<string>();
  const [found, setFound] = useState<{ variables: DetectedVariable[]; manual: boolean }>();
  const save = useAction(async (andRedeploy: boolean) => {
    setStatus(undefined);
    const body = envPayload(rows);
    await api(`/services/${serviceId}/env`, { method: "PUT", body });
    setRows(storedRows(body));
    setStatus(await saved(serviceId, andRedeploy));
  });
  const change = (next: typeof rows) => {
    setStatus(undefined);
    setRows(next);
  };
  const paste = useEnvPaste(rows, change);
  const check = useAction(async (manual: boolean) => {
    if (!repository) return;
    const detection = await api<Detection>(`/projects/${repository.projectId}/detect?ref=${encodeURIComponent(repository.branch)}`);
    const candidate = detection.services.find((s) => sameApp(s.spec, repository));
    setFound({ variables: candidate?.variables ?? [], manual });
  });
  const automatic = !!repository && initial.length === 0;
  const suggest = useEffectEvent(() => check.run(false));
  useEffect(() => {
    if (automatic) suggest();
  }, [automatic]);
  const missing = found?.variables.filter((v) => !rows.some((row) => row.name === v.name)) ?? [];
  const addRow = <Button onClick={() => change([...rows, { name: "", value: "", secret: false }])}>Add variable</Button>;
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        save.run(redeployRequested(e));
      }}
      className="flex flex-col gap-4"
    >
      <Card>
        <CardHeader
          title="Environment variables"
          description="Encrypted at rest and available to builds and running containers."
          actions={
            <>
              {rows.length > 0 && (
                <>
                  {addRow}
                  {paste.pasteButton}
                </>
              )}
              {paste.importButton}
              {repository && (
                <Button pending={check.pending} onClick={() => check.run(true)}>
                  Check repository
                </Button>
              )}
            </>
          }
        />
        {paste.panel}
        {found && (missing.length > 0 || found.manual) && (
          <div role="status" className="flex flex-wrap items-center justify-between gap-2 border-b border-graphite-700 px-6 py-3 text-sm max-sm:px-4">
            <span className="text-graphite-200">
              {missing.length > 0 ? `Found in ${[...new Set(missing.map((v) => v.source))].join(", ")}: ${missing.length} not set` : "Every variable the repository names is set."}
            </span>
            <span className="flex gap-2">
              {missing.length > 0 && (
                <Button
                  onClick={() => {
                    change([...rows, ...missing.map(detectedRow)]);
                    setFound(undefined);
                  }}
                >
                  Add all
                </Button>
              )}
              <Button variant="ghost" onClick={() => setFound(undefined)}>
                Dismiss
              </Button>
            </span>
          </div>
        )}
        {check.error && (
          <div className="border-b border-graphite-700 px-6 py-3 max-sm:px-4">
            <FormError message={check.error} />
          </div>
        )}
        {rows.length === 0 ? (
          <div className="p-6 max-sm:p-4">
            <EmptyState
              title="No variables"
              description="Paste a .env file or add variables one by one."
              action={
                <div className="flex flex-wrap justify-center gap-2">
                  <Button variant="primary" onClick={paste.open}>
                    Paste .env
                  </Button>
                  {addRow}
                </div>
              }
            />
          </div>
        ) : (
          <EnvRows rows={rows} onChange={change} databaseHref={databaseHref} />
        )}
      </Card>
      <FormError message={save.error} />
      <SaveActions pending={save.pending} status={status} />
    </form>
  );
}
