"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useApi, useRole } from "@/lib/hooks";
import type { ProjectTree } from "@/lib/types";
import { findService } from "@/lib/util";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/ui/badge";
import { ErrorState } from "@/components/ui/empty-state";
import { PageSkeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
import { BuildsTab } from "./builds-tab";
import { DeploymentsTab } from "./deployments-tab";
import { DomainsTab } from "./domains-tab";
import { EnvTab } from "./env-tab";
import { LogsTab } from "./logs-tab";
import { SettingsTab } from "./settings-tab";

const tabs = [
  { id: "deployments", label: "Deployments" },
  { id: "logs", label: "Logs" },
  { id: "builds", label: "Builds" },
  { id: "env", label: "Environment variables" },
  { id: "domains", label: "Domains" },
  { id: "settings", label: "Settings" },
] as const;

type Tab = (typeof tabs)[number]["id"];

async function loadService(org: string, projectSlug: string, environmentSlug: string, serviceSlug: string) {
  const found = findService(await api<ProjectTree>(`/orgs/${org}/projects/${projectSlug}/tree`), environmentSlug, serviceSlug);
  if (!found) throw new ApiError(404, "not_found", `There is no service called ${serviceSlug} in ${projectSlug}/${environmentSlug}.`);
  return found;
}

export function ServiceView({ org, projectSlug, environmentSlug, serviceSlug }: { org: string; projectSlug: string; environmentSlug: string; serviceSlug: string }) {
  const [tab, setTab] = useState<Tab>("deployments");
  const lookup = useApi(`${org}/${projectSlug}/${environmentSlug}/${serviceSlug}`, () => loadService(org, projectSlug, environmentSlug, serviceSlug));
  const { query: role, admin } = useRole(org);
  if (lookup.error) return <ErrorState error={lookup.error} retry={lookup.reload} />;
  if (role.error) return <ErrorState error={role.error} retry={role.reload} />;
  if (!lookup.data || role.loading) return <PageSkeleton />;
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
      <Tabs items={admin ? tabs : tabs.filter((t) => t.id !== "settings")} value={tab} onChange={setTab} />
      {tab === "deployments" && <DeploymentsTab service={service} admin={admin} />}
      {tab === "logs" && <LogsTab service={service} />}
      {tab === "builds" && <BuildsTab service={service} admin={admin} />}
      {tab === "env" && <EnvTab service={service} admin={admin} />}
      {tab === "domains" && <DomainsTab service={service} admin={admin} />}
      {tab === "settings" && <SettingsTab service={service} projectHref={`/${org}/${projectSlug}`} onChanged={lookup.reload} />}
    </div>
  );
}
