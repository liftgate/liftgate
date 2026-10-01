"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import { stackName } from "@/lib/stacks";
import type { Service, ServiceKind, ServiceSpec, Usage } from "@/lib/types";
import { applyAction, formValues, planName } from "@/lib/util";
import { DangerZone } from "@/components/danger-zone";
import { SaveActions, type Saved } from "@/components/save-actions";
import { BuildFields, GeneralFields, ResourceFields, RuntimeFields, toSpec } from "@/components/service-form";
import { SettingsLayout } from "@/components/settings-layout";
import { Card, CardHeader } from "@/components/ui/card";
import { FormError } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";

type ErrorAt = (field: string) => string | undefined;

const sections = [
  { id: "general", label: "General" },
  { id: "build", label: "Build" },
  { id: "runtime", label: "Runtime" },
  { id: "resources", label: "Resources" },
] as const;

export function SettingsTab({ org, service, section, projectHref, onChanged }: { org: string; service: Service; section: string | null; projectHref: string; onChanged: () => void }) {
  const pathname = usePathname();
  const router = useRouter();
  const [kind, setKind] = useState<ServiceKind>(service.kind);
  const usage = useApi<Usage>(section === "resources" && `/orgs/${org}/usage`);
  const remove = useAction(async () => {
    await api(`/services/${service.id}`, { method: "DELETE" });
    router.push(projectHref);
  });
  const current = sections.find((s) => s.id === section)?.id ?? "general";
  const card = { service, onChanged };
  const dir = service.rootDir.replace(/^\/+|\/+$/g, "");
  const dockerfile = service.buildStrategy === "dockerfile" || service.framework === "dockerfile";
  const of = (used: number, limit: number | null, unit: string) => `${used}${unit}${limit === null ? "" : ` of ${limit}${unit}`}`;
  const volume = service.volume ? ", domains and the files on its volume" : " and domains";
  return (
    <SettingsLayout sections={sections.map((s) => ({ ...s, href: `${pathname}?tab=settings&section=${s.id}` }))} current={current}>
      {current === "general" && (
        <>
          <SpecCard {...card} key="general" title="General" fields={["name", "kind", "cronSchedule"]}>
            {(errorAt) => <GeneralFields initial={service} kind={kind} onKind={setKind} errorAt={errorAt} />}
          </SpecCard>
          <DangerZone
            title="Delete service"
            description={`Removes the service with its deployments, variables${volume}.`}
            typed={service.slug}
            pending={remove.pending}
            error={remove.error}
            onConfirm={() => remove.run()}
          >
            <span className="font-medium text-white">{service.name}</span> is removed with its deployments, variables{volume}.
          </DangerZone>
        </>
      )}
      {current === "build" && (
        <SpecCard
          {...card}
          key="build"
          title="Build"
          description={dockerfile ? `Dockerfile at ./${dir ? `${dir}/` : ""}${service.dockerfilePath}` : [stackName(service.framework), "Railpack"].filter(Boolean).join(" · ")}
          fields={["rootDir", "buildCommand", "dockerfilePath", "watchPaths", "buildStrategy"]}
        >
          {(errorAt) => <BuildFields initial={service} dockerfile errorAt={errorAt} />}
        </SpecCard>
      )}
      {current === "runtime" && (
        <SpecCard {...card} key="runtime" title="Runtime" fields={["startCommand", "port", "healthCheckPath"]}>
          {(errorAt) => <RuntimeFields initial={service} kind={service.kind} startDefault="From the image" errorAt={errorAt} />}
        </SpecCard>
      )}
      {current === "resources" && (
        <SpecCard
          {...card}
          key="resources"
          title="Resources"
          description={
            usage.data ? (
              <>
                The organization uses {of(usage.data.cpuMillis, usage.data.limits.cpuMillis, "m")} CPU and {of(usage.data.memoryMb, usage.data.limits.memoryMb, " MB")} memory on
                the{" "}
                <Link href={`/${org}/settings`} className="text-graphite-200 underline underline-offset-2 hover:text-white">
                  {planName(usage.data.plan)} plan
                </Link>
                .
              </>
            ) : (
              <Skeleton className="h-4 w-72" />
            )
          }
          fields={["replicas", "cpuMillis", "memoryMb", "volume"]}
        >
          {(errorAt) => <ResourceFields initial={service} errorAt={errorAt} />}
        </SpecCard>
      )}
    </SettingsLayout>
  );
}

function SpecCard({
  service,
  title,
  description,
  fields,
  onChanged,
  children,
}: {
  service: Service;
  title: string;
  description?: ReactNode;
  fields: (keyof ServiceSpec)[];
  onChanged: () => void;
  children: (errorAt: ErrorAt) => ReactNode;
}) {
  const [saved, setSaved] = useState<Saved>();
  const save = useAction(async (form: HTMLFormElement) => {
    const spec = toSpec(formValues(form));
    const body = Object.fromEntries(fields.map((field) => [field, spec[field]]));
    setSaved(undefined);
    await api(`/services/${service.id}`, { method: "PATCH", body });
    setSaved({ current: service.current, apply: applyAction(service.current, service, body) });
    onChanged();
  });
  const inline = !!save.field && ([...fields, "slug", "storageGb"] as string[]).includes(save.field);
  return (
    <Card>
      <CardHeader title={title} description={description} />
      <form
        onSubmit={(e) => {
          e.preventDefault();
          save.run(e.currentTarget);
        }}
        className="flex flex-col gap-4 p-6"
      >
        {children((field) => (save.field === field ? save.error : undefined))}
        <FormError message={inline ? undefined : save.error} />
        <SaveActions serviceId={service.id} pending={save.pending} saved={saved} onApplied={onChanged} />
      </form>
    </Card>
  );
}
