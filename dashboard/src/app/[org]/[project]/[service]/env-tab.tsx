"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { EnvVar, Service } from "@/lib/types";
import { Loaded } from "@/components/loaded";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";

export function EnvTab({ service }: { service: Service }) {
  const vars = useApi<EnvVar[]>(`/services/${service.id}/env`);
  return (
    <Loaded query={vars} skeleton={<TableSkeleton />}>
      {(initial) => <EnvEditor serviceId={service.id} initial={initial} />}
    </Loaded>
  );
}

function EnvEditor({ serviceId, initial }: { serviceId: string; initial: EnvVar[] }) {
  const [rows, setRows] = useState(initial);
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => {
    const body = rows.map((r) => ({ ...r, value: r.secret && !r.value ? null : (r.value ?? "") }));
    await api(`/services/${serviceId}/env`, { method: "PUT", body });
    setRows(body.map((r) => (r.secret ? { ...r, value: null } : r)));
    setSaved(true);
  });
  const change = (next: EnvVar[]) => {
    setSaved(false);
    setRows(next);
  };
  const update = (i: number, patch: Partial<EnvVar>) => change(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const addRow = (
    <Button onClick={() => change([...rows, { name: "", value: "", secret: false }])}>Add variable</Button>
  );
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        save.run();
      }}
      className="flex flex-col gap-4"
    >
      <Card>
        <CardHeader
          title="Environment variables"
          description="Applied to every container of this service on its next release. Secret values are write-only."
          actions={addRow}
        />
        {rows.length === 0 ? (
          <div className="p-6">
            <EmptyState title="No variables" description="Variables are encrypted at rest and injected at runtime." action={addRow} />
          </div>
        ) : (
          <div className="divide-y divide-graphite-700">
            {rows.map((row, i) => (
              <div key={i} className="grid grid-cols-[1fr_2fr_auto_auto] items-center gap-4 px-6 py-2">
                <Input
                  aria-label="Name"
                  required
                  pattern="[A-Za-z_][A-Za-z0-9_]*"
                  placeholder="NAME"
                  value={row.name}
                  onChange={(e) => update(i, { name: e.target.value })}
                  className="font-mono"
                />
                <Input
                  aria-label="Value"
                  type={row.secret ? "password" : "text"}
                  placeholder={row.secret && row.value === null ? "Hidden. Type to replace." : "value"}
                  value={row.value ?? ""}
                  onChange={(e) => update(i, { value: e.target.value })}
                  className="font-mono"
                />
                <label className="flex items-center gap-2 text-sm text-graphite-200">
                  <input type="checkbox" checked={row.secret} onChange={(e) => update(i, { secret: e.target.checked })} className="accent-accent" />
                  Secret
                </label>
                <Button variant="ghost" onClick={() => change(rows.filter((_, j) => j !== i))}>
                  Remove
                </Button>
              </div>
            ))}
          </div>
        )}
      </Card>
      <div className="flex items-center gap-4">
        <Button type="submit" variant="primary" pending={save.pending}>
          Save changes
        </Button>
        {saved && <span className="text-sm text-graphite-400">Saved</span>}
        <FormError message={save.error} />
      </div>
    </form>
  );
}
