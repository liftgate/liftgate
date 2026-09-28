"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import type { EnvVar } from "@/lib/types";
import { envPayload, keepsStoredValue, storedRows } from "@/lib/util";
import { Button } from "./ui/button";
import { Card, CardHeader } from "./ui/card";
import { EmptyState } from "./ui/empty-state";
import { FormError, Input } from "./ui/input";

export function EnvEditor({ serviceId, initial }: { serviceId: string; initial: EnvVar[] }) {
  const [rows, setRows] = useState(() => storedRows(initial));
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => {
    const body = envPayload(rows);
    await api(`/services/${serviceId}/env`, { method: "PUT", body });
    setRows(storedRows(body));
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
              <div key={i} className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-2 px-6 py-3 md:grid-cols-[1fr_2fr_auto_auto] md:py-2">
                <Input
                  aria-label="Name"
                  required
                  pattern="[A-Za-z_][A-Za-z0-9_]*"
                  placeholder="NAME"
                  value={row.name}
                  onChange={(e) => update(i, { name: e.target.value })}
                  className="col-span-2 font-mono md:col-span-1"
                />
                <Input
                  aria-label="Value"
                  type={row.secret ? "password" : "text"}
                  placeholder={keepsStoredValue(row) ? "Hidden. Type to replace." : "value"}
                  value={row.value ?? ""}
                  onChange={(e) => update(i, { value: e.target.value })}
                  className="col-span-2 font-mono md:col-span-1"
                />
                <label className="flex items-center gap-2 text-sm text-graphite-200">
                  <input
                    type="checkbox"
                    checked={row.secret}
                    disabled={keepsStoredValue(row)}
                    onChange={(e) => update(i, { secret: e.target.checked })}
                    className="accent-accent"
                  />
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
