"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import type { Environment, Project, Service } from "@/lib/types";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/ui/badge";
import { ErrorState } from "@/components/ui/empty-state";
import { PageSkeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
import { BuildsTab } from "./builds-tab";
import { DeploymentsTab } from "./deployments-tab";
import { DomainsTab } from "./domains-tab";
import { EnvTab } from "./env-tab";
import { SettingsTab } from "./settings-tab";

const tabs = [
  { id: "deployments", label: "Deployments" },
  { id: "builds", label: "Builds" },
  { id: "env", label: "Environment variables" },
  { id: "domains", label: "Domains" },
  { id: "settings", label: "Settings" },
] as const;

type Tab = (typeof tabs)[number]["id"];

async function findService(org: string, projectSlug: string, serviceSlug: string) {
  const project = (await api<Project[]>(`/orgs/${org}/projects`)).find((p) => p.slug === projectSlug);
  if (!project) throw new ApiError(404, "not_found", `There is no project called ${projectSlug} in ${org}.`);
  const environments = await api<Environment[]>(`/projects/${project.id}/environments`);
  const lists = await Promise.all(environments.map((e) => api<Service[]>(`/environments/${e.id}/services`)));
  for (const [i, list] of lists.entries()) {
    const service = list.find((s) => s.slug === serviceSlug);
    if (service) return { project, environment: environments[i], service };
  }
  throw new ApiError(404, "not_found", `There is no service called ${serviceSlug} in ${projectSlug}.`);
}

export function ServiceView({ org, projectSlug, serviceSlug }: { org: string; projectSlug: string; serviceSlug: string }) {
  const [tab, setTab] = useState<Tab>("deployments");
  const lookup = useApi(`${org}/${projectSlug}/${serviceSlug}`, () => findService(org, projectSlug, serviceSlug));
  if (lookup.error) return <ErrorState error={lookup.error} retry={lookup.reload} />;
  if (!lookup.data) return <PageSkeleton />;
  const { service, environment } = lookup.data;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title={service.name}
        description={`${environment.name} · ${service.rootDir}`}
        actions={
          <>
            <StatusBadge status={service.kind} />
            <StatusBadge status={environment.kind} />
          </>
        }
      />
      <Tabs items={tabs} value={tab} onChange={setTab} />
      {tab === "deployments" && <DeploymentsTab service={service} />}
      {tab === "builds" && <BuildsTab service={service} />}
      {tab === "env" && <EnvTab service={service} />}
      {tab === "domains" && <DomainsTab service={service} />}
      {tab === "settings" && <SettingsTab service={service} projectHref={`/${org}/${projectSlug}`} onChanged={lookup.reload} />}
    </div>
  );
}
