"use client";

import { useState, type ReactNode } from "react";
import type { BuildStrategy, Service, ServiceKind, ServiceSpec } from "@/lib/types";
import { formValues, servesHttp } from "@/lib/util";
import { NameSlugFields } from "./name-slug-fields";
import { redeployRequested } from "./save-actions";
import { Button } from "./ui/button";
import { Field, FormError, Input, Textarea } from "./ui/input";
import { Select } from "./ui/select";

const kinds: ServiceKind[] = ["web", "worker", "cron", "static"];
const strategies: BuildStrategy[] = ["auto", "dockerfile"];
const insideRepo = String.raw`(?!(.*\/)?\.\.(\/|$))[A-Za-z0-9._\/\-]*`;
const buildFields = ["dockerfilePath", "port", "healthCheckPath", "watchPaths"];
const resourceFields = ["replicas", "cpuMillis", "memoryMb"];

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
  healthCheckPath: v.healthCheckPath || null,
  watchPaths: v.watchPaths.split("\n").map((path) => path.trim()).filter(Boolean),
});

const watchPathsError = (text: string) => {
  const paths = text.split("\n").filter((path) => path.trim());
  if (paths.length > 20) return "List at most 20 watch paths.";
  return paths.some((path) => path.trim().length > 100) ? "Keep each watch path to 100 characters." : "";
};

export function ServiceForm({
  initial,
  before,
  prefill,
  hostFor,
  pending,
  error,
  errorField,
  submitLabel,
  actions,
  onSubmit,
  onCancel,
}: {
  initial?: Service;
  before?: ReactNode;
  prefill?: string;
  hostFor?: (slug: string) => string | undefined;
  pending: boolean;
  error?: string;
  errorField?: string;
  submitLabel?: string;
  actions?: ReactNode;
  onSubmit: (spec: ServiceSpec, values: Record<string, string>, redeploy: boolean) => void;
  onCancel?: () => void;
}) {
  const [kind, setKind] = useState<ServiceKind>(initial?.kind ?? "web");
  const [strategy, setStrategy] = useState<BuildStrategy>(initial?.buildStrategy ?? "auto");
  const [port, setPort] = useState(initial?.port?.toString() ?? "");
  const at = (field: string) => (errorField === field ? error : undefined);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const values = formValues(e.currentTarget);
        onSubmit(toSpec(values), values, redeployRequested(e));
      }}
      onInvalidCapture={(e) => (e.target as HTMLElement).closest("details")?.setAttribute("open", "")}
      className="flex flex-col gap-4"
    >
      {before}
      <NameSlugFields
        initial={initial}
        prefill={prefill}
        errorAt={at}
        preview={hostFor && servesHttp(kind) ? (slug) => <UrlPreview host={hostFor(slug)} /> : undefined}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Kind">
          <Select name="kind" value={kind} onChange={(e) => setKind(e.target.value as ServiceKind)}>
            {kinds.map((kind) => (
              <option key={kind} value={kind}>
                {kind}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Root directory" hint="Where the service lives in the repository" error={at("rootDir")}>
          <Input name="rootDir" defaultValue={initial?.rootDir ?? "/"} pattern={insideRepo} className="font-mono" />
        </Field>
        {kind === "cron" && (
          <Field label="Cron schedule" error={at("cronSchedule")}>
            <Input name="cronSchedule" required placeholder="*/5 * * * *" defaultValue={initial?.cronSchedule ?? ""} className="font-mono" />
          </Field>
        )}
      </div>
      <Section title="Build and runtime" hint="Strategy, port, start command, health check" open={!!initial} failed={!!errorField && buildFields.includes(errorField)}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Build strategy" hint="Auto detects the stack with Railpack">
            <Select name="buildStrategy" value={strategy} onChange={(e) => setStrategy(e.target.value as BuildStrategy)}>
              {strategies.map((strategy) => (
                <option key={strategy} value={strategy}>
                  {strategy}
                </option>
              ))}
            </Select>
          </Field>
          {strategy === "dockerfile" && (
            <Field label="Dockerfile path" hint="Relative to the root directory" error={at("dockerfilePath")}>
              <Input
                name="dockerfilePath"
                required
                pattern={`(?!\\/)${insideRepo}`}
                defaultValue={initial?.dockerfilePath ?? "Dockerfile"}
                className="font-mono"
              />
            </Field>
          )}
          {kind !== "cron" && (
            <Field
              label="Port"
              hint={servesHttp(kind) ? "Your app should listen on $PORT, which is 8080 unless set here" : "Optional. Lets other services in this environment reach the worker"}
              error={at("port")}
            >
              <Input name="port" type="number" min={1} max={65535} placeholder={servesHttp(kind) ? "8080" : undefined} value={port} onChange={(e) => setPort(e.target.value)} />
            </Field>
          )}
          <Field label="Start command" hint="Overrides the image entrypoint">
            <Input name="startCommand" defaultValue={initial?.startCommand ?? ""} className="font-mono" />
          </Field>
          {(servesHttp(kind) || (kind !== "cron" && !!port)) && (
            <Field label="Health check path" hint="Probed over HTTP on the port; empty only checks that the port accepts connections" error={at("healthCheckPath")}>
              <Input
                name="healthCheckPath"
                placeholder="/healthz"
                pattern="\/.*"
                maxLength={256}
                defaultValue={initial?.healthCheckPath ?? ""}
                className="font-mono"
              />
            </Field>
          )}
        </div>
        <Field label="Watch paths" hint="One glob per line. A push that changes no matching file skips this service; empty watches the root directory" error={at("watchPaths")}>
          <Textarea
            name="watchPaths"
            rows={3}
            placeholder={"apps/web/**\npackages/ui/**"}
            defaultValue={initial?.watchPaths.join("\n") ?? ""}
            onInput={(e) => e.currentTarget.setCustomValidity(watchPathsError(e.currentTarget.value))}
            className="font-mono"
          />
        </Field>
      </Section>
      <Section title="Resources" hint="Replicas, CPU and memory" open={false} failed={!!errorField && resourceFields.includes(errorField)}>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Replicas" error={at("replicas")}>
            <Input name="replicas" type="number" min={0} max={10} required defaultValue={initial?.replicas ?? 1} />
          </Field>
          <Field label="CPU (millicores)" error={at("cpuMillis")}>
            <Input name="cpuMillis" type="number" min={1} max={4000} required defaultValue={initial?.cpuMillis ?? 500} />
          </Field>
          <Field label="Memory (MB)" error={at("memoryMb")}>
            <Input name="memoryMb" type="number" min={1} max={8192} required defaultValue={initial?.memoryMb ?? 512} />
          </Field>
        </div>
      </Section>
      <FormError message={errorField ? undefined : error} />
      <div className="flex justify-end gap-2">
        {onCancel && <Button onClick={onCancel}>Cancel</Button>}
        {actions ?? (
          <Button type="submit" variant="primary" pending={pending}>
            {submitLabel}
          </Button>
        )}
      </div>
    </form>
  );
}

function UrlPreview({ host }: { host?: string }) {
  return (
    <p className="text-xs text-graphite-400">
      {host ? (
        <>
          Serves at <span className="font-mono text-graphite-200">https://{host}</span>
        </>
      ) : (
        "Too long for a readable hostname, so it gets a shortened one with a suffix."
      )}
    </p>
  );
}

function Section({ title, hint, open: initiallyOpen, failed, children }: { title: string; hint: string; open: boolean; failed: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(initiallyOpen);
  const [shown, setShown] = useState(failed);
  if (failed !== shown) {
    setShown(failed);
    if (failed) setOpen(true);
  }
  return (
    <details open={open} onToggle={(e) => setOpen(e.currentTarget.open)} className="group rounded-lg border border-graphite-700">
      <summary className="flex cursor-pointer list-none items-center gap-2 rounded-lg px-4 py-3 text-sm focus-visible:outline-2 focus-visible:outline-accent [&::-webkit-details-marker]:hidden">
        <svg viewBox="0 0 16 16" aria-hidden className="size-4 shrink-0 text-graphite-400 transition-transform group-open:rotate-90">
          <path d="M6 4l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="font-medium text-graphite-200">{title}</span>
        <span className="truncate text-xs text-graphite-400 max-sm:hidden">{hint}</span>
      </summary>
      <div className="flex flex-col gap-4 border-t border-graphite-700 p-4">{children}</div>
    </details>
  );
}
