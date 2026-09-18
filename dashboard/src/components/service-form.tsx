"use client";

import type { ReactNode } from "react";
import type { BuildStrategy, Service, ServiceKind, ServiceSpec } from "@/lib/types";
import { formValues } from "@/lib/util";
import { NameSlugFields } from "./name-slug-fields";
import { Button } from "./ui/button";
import { Field, FormError, Input } from "./ui/input";
import { Select } from "./ui/select";

const kinds: ServiceKind[] = ["web", "worker", "cron", "static"];
const strategies: BuildStrategy[] = ["auto", "dockerfile"];

const toSpec = (v: Record<string, string>): ServiceSpec => ({
  slug: v.slug,
  name: v.name,
  kind: v.kind as ServiceKind,
  rootDir: v.rootDir || "/",
  buildStrategy: v.buildStrategy as BuildStrategy,
  dockerfilePath: v.dockerfilePath || "Dockerfile",
  port: v.port ? Number(v.port) : null,
  replicas: Number(v.replicas),
  cpuMillis: Number(v.cpuMillis),
  memoryMb: Number(v.memoryMb),
  cronSchedule: v.cronSchedule || null,
  startCommand: v.startCommand || null,
});

export function ServiceForm({
  initial,
  before,
  pending,
  error,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initial?: Service;
  before?: ReactNode;
  pending: boolean;
  error?: string;
  submitLabel: string;
  onSubmit: (spec: ServiceSpec, values: Record<string, string>) => void;
  onCancel?: () => void;
}) {
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const values = formValues(e.currentTarget);
        onSubmit(toSpec(values), values);
      }}
      className="flex flex-col gap-4"
    >
      {before}
      <NameSlugFields initial={initial} />
      <div className="grid grid-cols-2 gap-4">
        <Field label="Kind">
          <Select name="kind" defaultValue={initial?.kind ?? "web"}>
            {kinds.map((kind) => (
              <option key={kind} value={kind}>
                {kind}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Port" hint="Web services only">
          <Input name="port" type="number" min={1} max={65535} defaultValue={initial?.port ?? ""} />
        </Field>
        <Field label="Root directory">
          <Input name="rootDir" defaultValue={initial?.rootDir ?? "/"} className="font-mono" />
        </Field>
        <Field label="Build strategy" hint="Auto detects the stack with Railpack">
          <Select name="buildStrategy" defaultValue={initial?.buildStrategy ?? "auto"}>
            {strategies.map((strategy) => (
              <option key={strategy} value={strategy}>
                {strategy}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Dockerfile path">
          <Input name="dockerfilePath" defaultValue={initial?.dockerfilePath ?? "Dockerfile"} className="font-mono" />
        </Field>
        <Field label="Replicas">
          <Input name="replicas" type="number" min={0} max={20} required defaultValue={initial?.replicas ?? 1} />
        </Field>
        <Field label="CPU (millicores)">
          <Input name="cpuMillis" type="number" min={100} step={100} required defaultValue={initial?.cpuMillis ?? 500} />
        </Field>
        <Field label="Memory (MB)">
          <Input name="memoryMb" type="number" min={128} step={128} required defaultValue={initial?.memoryMb ?? 512} />
        </Field>
        <Field label="Cron schedule" hint="Cron services only">
          <Input name="cronSchedule" placeholder="*/5 * * * *" defaultValue={initial?.cronSchedule ?? ""} className="font-mono" />
        </Field>
        <Field label="Start command" hint="Overrides the image entrypoint">
          <Input name="startCommand" defaultValue={initial?.startCommand ?? ""} className="font-mono" />
        </Field>
      </div>
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        {onCancel && <Button onClick={onCancel}>Cancel</Button>}
        <Button type="submit" variant="primary" pending={pending}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
