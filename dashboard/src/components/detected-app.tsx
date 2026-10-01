"use client";

import { useId, useRef, useState } from "react";
import { stackIcon } from "@/lib/stacks";
import type { Detection, DetectedService, ServiceKind } from "@/lib/types";
import { formValues, kindLabels, servesHttp } from "@/lib/util";
import { BuildFields, GeneralFields, ResourceFields, RuntimeFields } from "./service-form";
import { Button } from "./ui/button";

export const SET_START = "Set a start command";

export const directoryOf = (detected: DetectedService | null) =>
  !detected ? "/" : detected.spec.rootDir !== "/" ? detected.spec.rootDir : (detected.spec.watchPaths[0]?.replace(/\/\*\*$/, "") ?? "/");

export function DetectedApp({
  detected,
  directories,
  multi,
  checked,
  onCheck,
  prefill,
  hostFor,
  showUrl,
  resources,
  failure,
  formRef,
  onSwap,
}: {
  detected: DetectedService | null;
  directories: Detection["directories"];
  multi: boolean;
  checked: boolean;
  onCheck: (checked: boolean) => void;
  prefill: { name: string; slug: string };
  hostFor: (slug: string) => string | undefined;
  showUrl: boolean;
  resources: { cpuMillis: number; memoryMb: number };
  failure?: { message: string; field?: string };
  formRef: (form: HTMLFormElement | null) => void;
  onSwap: (directory: string) => void;
}) {
  const spec = detected?.spec;
  const id = useId();
  const form = useRef<HTMLFormElement>(null);
  const [kind, setKind] = useState<ServiceKind>(spec?.kind ?? "web");
  const [open, setOpen] = useState(!detected?.framework);
  const [values, setValues] = useState<Record<string, string>>({});
  const [shownFailure, setShownFailure] = useState(failure);
  const [shownPrefill, setShownPrefill] = useState(prefill);
  if (failure !== shownFailure) {
    setShownFailure(failure);
    if (failure?.field) setOpen(true);
  }
  if (prefill.name !== shownPrefill.name || prefill.slug !== shownPrefill.slug) {
    setShownPrefill(prefill);
    setValues((all) => ({ ...all, ...prefill }));
  }
  const read = () => form.current && setValues(formValues(form.current));
  const value = (field: string, fallback?: string | number | null) => values[field] ?? (fallback == null ? "" : String(fallback));
  const directory = directoryOf(detected);
  const framework = detected?.framework;
  const dockerfile = detected?.builder === "dockerfile";
  const icon = stackIcon(framework?.id);
  const name = value("name", prefill.name);
  const title = !detected
    ? "Not detected"
    : !framework
      ? `No app found at ${directory}`
      : dockerfile && framework.id !== "dockerfile"
        ? `${framework.name} · Dockerfile`
        : framework.name;
  const port = value("port", spec?.port);
  const health = value("healthCheckPath", spec?.healthCheckPath);
  const host = servesHttp(kind) ? hostFor(value("slug", prefill.slug)) : undefined;
  const railpack = (command?: string | null) => (command ? `${command} (Railpack)` : "Railpack");
  const facts = [
    ["Build", dockerfile ? value("dockerfilePath", spec?.dockerfilePath) : value("buildCommand", spec?.buildCommand) || railpack(detected?.defaults.build)],
    ["Start", value("startCommand", spec?.startCommand) || (dockerfile ? "Image CMD" : railpack(detected?.defaults.start))],
    ["Health", kind === "cron" || (!servesHttp(kind) && !port) ? "None" : health ? `HTTP ${health}` : `TCP on ${port || "$PORT"}`],
    ...(host && showUrl ? [["URL", `https://${host}`]] : []),
  ];
  const brief = multi && !detected?.selected && !open;
  const applyHint = (hint: string) => {
    const input = form.current?.elements.namedItem("healthCheckPath");
    if (input instanceof HTMLInputElement) input.value = hint;
    read();
  };
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-start gap-4">
        {multi && (
          <input type="checkbox" aria-label={`Deploy ${name}`} checked={checked} onChange={(e) => onCheck(e.target.checked)} className="mt-3 size-4 shrink-0 accent-accent" />
        )}
        <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-md bg-graphite-800 text-graphite-200">
          <svg viewBox="0 0 24 24" className="size-5 fill-current">
            <path d={icon?.path ?? "M4 4h16v16H4zm2 2v12h12V6z"} />
          </svg>
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <p className="truncate text-base font-medium">{multi ? name : title}</p>
          <p className="text-xs break-words text-graphite-400">
            {(multi ? [directory, framework && title, kindLabels[kind]] : [kindLabels[kind], directory, detected?.packageManager, dockerfile ? "Dockerfile" : "Railpack"]).filter(Boolean).join(" · ")}
          </p>
        </div>
        <Button variant="ghost" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
          {open ? "Done" : "Edit"}
        </Button>
      </div>
      {!brief && (
        <div className="flex flex-col gap-3">
          <dl className="flex flex-wrap gap-x-8 gap-y-1 text-xs max-sm:flex-col">
            {facts.map(([label, fact]) => (
              <div key={label} className="flex min-w-0 max-w-full gap-2">
                <dt className="shrink-0 text-graphite-400">{label}</dt>
                <dd className="truncate font-mono text-graphite-200" title={fact}>
                  {fact}
                </dd>
              </div>
            ))}
          </dl>
          {detected && (
            <div className="flex flex-col gap-1 text-xs">
              <p className="text-graphite-400">{detected.evidence}</p>
              {detected.warnings.map((warning) => (
                <p key={warning} className="text-warning">
                  {warning}
                </p>
              ))}
              {detected.healthHint && framework && !health && (
                <p className="text-graphite-400">
                  {framework.name} serves <span className="font-mono">{detected.healthHint}</span>.{" "}
                  <button type="button" onClick={() => applyHint(detected.healthHint ?? "")} className="rounded-sm text-graphite-200 underline underline-offset-2 hover:text-white focus-visible:outline-2 focus-visible:outline-accent">
                    Use {detected.healthHint}
                  </button>
                </p>
              )}
            </div>
          )}
        </div>
      )}
      {failure && (
        <p role="alert" className="text-sm text-danger">
          {failure.message}
        </p>
      )}
      <form
        id={id}
        ref={(element) => {
          form.current = element;
          formRef(element);
        }}
        hidden={!open}
        aria-label={`Settings for ${name}`}
        onChange={read}
        onSubmit={(e) => e.preventDefault()}
        onInvalidCapture={() => {
          if (open) return;
          setOpen(true);
          setTimeout(() => form.current?.reportValidity());
        }}
        className="flex flex-col gap-4 rounded-lg border border-graphite-700 p-4"
      >
        <GeneralFields
          initial={spec}
          prefill={prefill}
          kind={kind}
          onKind={setKind}
          hostFor={hostFor}
          errorAt={(field) => (failure?.field === field ? failure.message : undefined)}
        />
        <BuildFields
          initial={spec}
          buildDefault={detected?.defaults.build}
          dockerfile={dockerfile}
          directories={directories}
          directory={directory}
          onDirectory={onSwap}
          errorAt={(field) => (failure?.field === field ? failure.message : undefined)}
        />
        <RuntimeFields
          initial={spec}
          kind={kind}
          startDefault={dockerfile ? "Image CMD" : (detected?.defaults.start ?? "Railpack default")}
          startRequired={!!detected?.warnings.includes(SET_START)}
          errorAt={(field) => (failure?.field === field ? failure.message : undefined)}
        />
        <ResourceFields initial={{ ...spec, ...resources }} errorAt={(field) => (failure?.field === field ? failure.message : undefined)} />
      </form>
    </div>
  );
}
