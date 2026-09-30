"use client";

import { usePathname, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAction, useApi, usePolling, useRole } from "@/lib/hooks";
import type { ProjectTree } from "@/lib/types";
import { findService, shortSha, timeAgo } from "@/lib/util";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/ui/badge";
import { Button, buttonClasses } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { PageSkeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
import { BuildsTab } from "./builds-tab";
import { DeploymentsTab } from "./deployments-tab";
import { DomainsTab } from "./domains-tab";
import { EnvTab } from "./env-tab";
import { LogsTab } from "./logs-tab";
import { MetricsTab } from "./metrics-tab";
import { SettingsTab } from "./settings-tab";

const tabs = [
  { id: "deployments", label: "Deployments" },
  { id: "logs", label: "Logs" },
  { id: "metrics", label: "Metrics" },
  { id: "builds", label: "Builds" },
  { id: "env", label: "Variables" },
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
  const pathname = usePathname();
  const search = useSearchParams();
  const [round, setRound] = useState(0);
  const lookup = useApi(`${org}/${projectSlug}/${environmentSlug}/${serviceSlug}`, () => loadService(org, projectSlug, environmentSlug, serviceSlug));
  const { query: role, admin } = useRole(org);
  const current = lookup.data?.service.current;
  const visible = admin ? tabs : tabs.filter((t) => t.id !== "settings");
  const tab = visible.find((t) => t.id === search.get("tab"))?.id ?? "deployments";
  const show = (id: Tab, build?: string) => window.history.replaceState(null, "", `${pathname}?${new URLSearchParams(build ? { tab: id, build } : { tab: id })}`);
  const redeploy = useAction(async (id: string) => {
    await api(`/services/${id}/redeploy`, { method: "POST" });
    show("deployments");
    setRound((r) => r + 1);
    lookup.reload();
  });
  usePolling(!!lookup.data && (!current || current.status === "pending" || current.status === "releasing"), lookup.reload);
  if (lookup.error) return <ErrorState error={lookup.error} retry={lookup.reload} />;
  if (role.error) return <ErrorState error={role.error} retry={role.reload} />;
  if (!lookup.data || role.loading) return <PageSkeleton />;
  const { service, environment } = lookup.data;
  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-col gap-2">
        <PageHeader
          title={service.name}
          description={[environment.name, ...(current ? [shortSha(current.commitSha), `deployed ${timeAgo(current.createdAt)}`] : ["not deployed yet"])].join(" · ")}
          actions={
            <>
              <StatusBadge status={current?.status ?? "not deployed"} />
              {current?.status === "running" && (
                <>
                  {service.url && (
                    <a href={service.url} target="_blank" rel="noreferrer" className={buttonClasses()}>
                      Visit
                    </a>
                  )}
                  {admin && (
                    <Button pending={redeploy.pending} onClick={() => redeploy.run(service.id)}>
                      Redeploy
                    </Button>
                  )}
                </>
              )}
            </>
          }
        />
        <FormError message={redeploy.error} />
      </div>
      <Tabs items={visible} value={tab} label={`${service.name} sections`} onChange={(id) => show(id)}>
        {tab === "deployments" && <DeploymentsTab key={round} service={service} admin={admin} onBuild={(id) => show("builds", id)} onChanged={lookup.reload} />}
        {tab === "logs" && <LogsTab service={service} />}
        {tab === "metrics" && <MetricsTab service={service} />}
        {tab === "builds" && <BuildsTab service={service} admin={admin} linked={search.get("build") ?? undefined} onSelect={(id) => show("builds", id)} />}
        {tab === "env" && <EnvTab service={service} admin={admin} />}
        {tab === "domains" && <DomainsTab service={service} admin={admin} />}
        {tab === "settings" && <SettingsTab service={service} projectHref={`/${org}/${projectSlug}`} onChanged={lookup.reload} />}
      </Tabs>
    </div>
  );
}
