"use client";

import { useState, type ReactNode } from "react";
import { useApi } from "@/lib/hooks";
import type { AuthProviders, BuildStrategy, Detection, Service, ServiceKind, ServiceSpec } from "@/lib/types";
import { formValues, kindLabels, servesHttp } from "@/lib/util";
import { DocsLink } from "./docs-link";
import { NameSlugFields } from "./name-slug-fields";
import { redeployRequested } from "./save-actions";
import { Field, FormError, Input, Textarea } from "./ui/input";
import { Select } from "./ui/select";

const kinds: ServiceKind[] = ["web", "static", "worker", "cron"];
const strategies: BuildStrategy[] = ["auto", "dockerfile"];
const insideRepo = String.raw`(?!(.*\/)?\.\.(\/|$))[A-Za-z0-9._\/\-]*`;
const buildFields = ["rootDir", "buildCommand", "dockerfilePath", "watchPaths", "startCommand", "port", "healthCheckPath"];
const resourceFields = ["replicas", "cpuMillis", "memoryMb", "volume", "storageGb"];

type ErrorAt = (field: string) => string | undefined;

export const toSpec = (v: Record<string, string>): ServiceSpec => ({
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
  watchPaths: (v.watchPaths ?? "").split("\n").map((path) => path.trim()).filter(Boolean),
  volume: v.volumeMountPath ? { mountPath: v.volumeMountPath, sizeGb: Number(v.volumeSizeGb) } : null,
  buildCommand: v.buildCommand || null,
});

const watchPathsError = (text: string) => {
  const paths = text.split("\n").filter((path) => path.trim());
  if (paths.length > 20) return "List at most 20 watch paths.";
  return paths.some((path) => path.trim().length > 100) ? "Keep each watch path to 100 characters." : "";
};

export function GeneralFields({
  initial,
  prefill,
  kind,
  onKind,
  hostFor,
  errorAt,
}: {
  initial?: Partial<ServiceSpec> & { name: string; slug: string };
  prefill?: { name: string; slug: string };
  kind: ServiceKind;
  onKind: (kind: ServiceKind) => void;
  hostFor?: (slug: string) => string | undefined;
  errorAt?: ErrorAt;
}) {
  const creating = prefill !== undefined;
  return (
    <>
      <NameSlugFields
        key={prefill && `${prefill.name}\n${prefill.slug}`}
        initial={creating ? undefined : initial}
        prefill={prefill?.name}
        prefillSlug={prefill?.slug}
        label={creating ? "Service name" : "Name"}
        compact={creating}
        errorAt={errorAt}
        preview={hostFor && servesHttp(kind) ? (slug) => <UrlPreview host={hostFor(slug)} /> : undefined}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Runs as">
          <Select name="kind" value={kind} onChange={(e) => onKind(e.target.value as ServiceKind)}>
            {kinds.map((kind) => (
              <option key={kind} value={kind}>
                {kindLabels[kind]}
              </option>
            ))}
          </Select>
        </Field>
        {kind === "cron" && (
          <Field label="Cron schedule" error={errorAt?.("cronSchedule")}>
            <Input name="cronSchedule" required pattern=".*\S.*" placeholder="*/5 * * * *" defaultValue={initial?.cronSchedule ?? ""} className="font-mono" />
          </Field>
        )}
      </div>
    </>
  );
}

export function BuildFields({
  initial,
  withStrategy = false,
  buildDefault,
  dockerfile = false,
  directories,
  directory,
  onDirectory,
  errorAt,
}: {
  initial?: Partial<ServiceSpec>;
  withStrategy?: boolean;
  buildDefault?: string | null;
  dockerfile?: boolean;
  directories?: Detection["directories"];
  directory?: string;
  onDirectory?: (path: string) => void;
  errorAt?: ErrorAt;
}) {
  const [strategy, setStrategy] = useState<BuildStrategy>(initial?.buildStrategy ?? "auto");
  const [other, setOther] = useState(!!directories && !directories.some((d) => d.path === directory));
  const rootInput = (
    <Input name="rootDir" defaultValue={initial?.rootDir ?? "/"} pattern={insideRepo} className="font-mono" />
  );
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Root directory" hint={directories ? undefined : "Where the service lives in the repository"} error={errorAt?.("rootDir")}>
          {directories?.length ? (
            <Select
              value={other ? "" : directory}
              onChange={(e) => {
                setOther(!e.target.value);
                if (e.target.value) onDirectory?.(e.target.value);
              }}
              className="font-mono"
            >
              {directories.map((d) => (
                <option key={d.path} value={d.path}>
                  {d.path}
                </option>
              ))}
              <option value="">Other…</option>
            </Select>
          ) : (
            rootInput
          )}
        </Field>
        {!!directories?.length && (other ? <Field label="Path">{rootInput}</Field> : <input type="hidden" name="rootDir" value={initial?.rootDir ?? "/"} />)}
        <Field label="Build command" error={errorAt?.("buildCommand")}>
          <Input name="buildCommand" defaultValue={initial?.buildCommand ?? ""} placeholder={buildDefault ?? "Railpack default"} maxLength={1000} className="font-mono" />
        </Field>
        {withStrategy && (
          <Field label="Build strategy">
            <Select name="buildStrategy" value={strategy} onChange={(e) => setStrategy(e.target.value as BuildStrategy)}>
              {strategies.map((strategy) => (
                <option key={strategy} value={strategy}>
                  {strategy}
                </option>
              ))}
            </Select>
          </Field>
        )}
        {(dockerfile || (withStrategy && strategy === "dockerfile")) && (
          <Field label="Dockerfile path" hint="Relative to the root directory" error={errorAt?.("dockerfilePath")}>
            <Input name="dockerfilePath" required pattern={`(?!\\/)${insideRepo}`} defaultValue={initial?.dockerfilePath ?? "Dockerfile"} className="font-mono" />
          </Field>
        )}
      </div>
      <Field label="Watch paths" hint="One glob per line. A push that changes no matching file skips this service; empty watches the root directory" error={errorAt?.("watchPaths")}>
        <Textarea
          name="watchPaths"
          rows={3}
          placeholder={"apps/web/**\npackages/ui/**"}
          defaultValue={initial?.watchPaths?.join("\n") ?? ""}
          onInput={(e) => e.currentTarget.setCustomValidity(watchPathsError(e.currentTarget.value))}
          className="font-mono"
        />
      </Field>
    </>
  );
}

export function RuntimeFields({
  initial,
  kind,
  startDefault,
  startRequired = false,
  errorAt,
}: {
  initial?: Partial<ServiceSpec>;
  kind: ServiceKind;
  startDefault?: string | null;
  startRequired?: boolean;
  errorAt?: ErrorAt;
}) {
  const [port, setPort] = useState(initial?.port?.toString() ?? "");
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Field label="Start command" hint={initial && startDefault === undefined ? "Overrides the image entrypoint" : undefined} error={errorAt?.("startCommand")}>
        <Input name="startCommand" required={startRequired} pattern={startRequired ? String.raw`.*\S.*` : undefined} defaultValue={initial?.startCommand ?? ""} placeholder={startDefault ?? undefined} className="font-mono" />
      </Field>
      {kind !== "cron" && (
        <Field
          label="Port"
          hint={
            <>
              {servesHttp(kind) ? "Empty uses $PORT, which is 8080." : "Optional. Lets other services in this environment reach the worker."}{" "}
              <DocsLink page="runtime-contract">Runtime contract</DocsLink>
            </>
          }
          error={errorAt?.("port")}
        >
          <Input name="port" type="number" min={1} max={65535} placeholder={servesHttp(kind) ? "8080" : undefined} value={port} onChange={(e) => setPort(e.target.value)} />
        </Field>
      )}
      {(servesHttp(kind) || (kind !== "cron" && !!port)) && (
        <Field label="Health check path" hint="Empty checks that the port accepts connections." error={errorAt?.("healthCheckPath")}>
          <Input name="healthCheckPath" placeholder="/healthz" pattern="\/.*" maxLength={256} defaultValue={initial?.healthCheckPath ?? ""} className="font-mono" />
        </Field>
      )}
    </div>
  );
}

export function ResourceFields({ initial, errorAt }: { initial?: Partial<ServiceSpec>; errorAt?: ErrorAt }) {
  const [volumePath, setVolumePath] = useState(initial?.volume?.mountPath ?? "");
  const storage = useApi<AuthProviders>("/auth/providers").data?.storage;
  const stored = initial?.volume ?? undefined;
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Replicas" hint={volumePath ? "At most 1 with a volume" : undefined} error={errorAt?.("replicas")}>
          <Input name="replicas" type="number" min={0} max={volumePath ? 1 : 10} required defaultValue={initial?.replicas ?? 1} />
        </Field>
        <Field label="CPU (millicores)" error={errorAt?.("cpuMillis")}>
          <Input name="cpuMillis" type="number" min={1} max={4000} required defaultValue={initial?.cpuMillis ?? 500} />
        </Field>
        <Field label="Memory (MB)" error={errorAt?.("memoryMb")}>
          <Input name="memoryMb" type="number" min={1} max={8192} required defaultValue={initial?.memoryMb ?? 512} />
        </Field>
      </div>
      {storage || stored ? (
        <div className="grid gap-4 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <Field
              label="Volume path"
              hint={stored ? "A volume can grow and stays until the service is deleted" : "Optional. Files here survive deploys, and the service restarts instead of rolling"}
              error={errorAt?.("volume")}
            >
              <Input
                name="volumeMountPath"
                placeholder="/data"
                pattern="\/.+"
                required={!!stored}
                value={volumePath}
                onChange={(e) => setVolumePath(e.target.value)}
                className="w-full font-mono"
              />
            </Field>
          </div>
          <Field label="Volume size (GB)" error={errorAt?.("storageGb")}>
            <Input name="volumeSizeGb" type="number" min={stored?.sizeGb ?? 1} max={100} required disabled={!volumePath} defaultValue={stored?.sizeGb ?? 1} />
          </Field>
        </div>
      ) : (
        storage === false && (
          <p className="text-sm text-graphite-400">
            Volumes need a storage class that enforces capacity, and this installation has none configured.{" "}
            <DocsLink page="self-hosting">Storage in the self-hosting guide</DocsLink>
          </p>
        )
      )}
    </>
  );
}

export function ServiceForm({
  initial,
  error,
  errorField,
  actions,
  onSubmit,
}: {
  initial: Service;
  error?: string;
  errorField?: string;
  actions: ReactNode;
  onSubmit: (spec: ServiceSpec, redeploy: boolean) => void;
}) {
  const [kind, setKind] = useState<ServiceKind>(initial.kind);
  const at = (field: string) => (errorField === field ? error : undefined);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(toSpec(formValues(e.currentTarget)), redeployRequested(e));
      }}
      onInvalidCapture={(e) => (e.target as HTMLElement).closest("details")?.setAttribute("open", "")}
      className="flex flex-col gap-4"
    >
      <GeneralFields initial={initial} kind={kind} onKind={setKind} errorAt={at} />
      <Section title="Build and runtime" hint="Root directory, commands, port, health check" open failed={!!errorField && buildFields.includes(errorField)}>
        <BuildFields initial={initial} withStrategy errorAt={at} />
        <RuntimeFields initial={initial} kind={kind} errorAt={at} />
      </Section>
      <Section title="Resources" hint="Replicas, CPU, memory and a volume" open={false} failed={!!errorField && resourceFields.includes(errorField)}>
        <ResourceFields initial={initial} errorAt={at} />
      </Section>
      <FormError message={errorField ? undefined : error} />
      <div className="flex justify-end gap-2">{actions}</div>
    </form>
  );
}

function UrlPreview({ host }: { host?: string }) {
  return host ? (
    <>
      Serves at <span className="font-mono text-graphite-200">https://{host}</span>
    </>
  ) : (
    "Too long for a readable hostname, so it gets a shortened one with a suffix."
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
