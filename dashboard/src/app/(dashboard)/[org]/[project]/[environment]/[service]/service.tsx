"use client";

import { usePathname, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAction, useApi, usePolling, useRole } from "@/lib/hooks";
import { stackIcon } from "@/lib/stacks";
import type { Build, ProjectTree } from "@/lib/types";
import { findService, formValues, shortSha, timeAgo } from "@/lib/util";
import { Icon, StackIcon } from "@/components/icons";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/ui/badge";
import { Button, buttonClasses } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/empty-state";
import { Field, FormError, Input } from "@/components/ui/input";
import { Menu, MenuButton } from "@/components/ui/menu";
import { PageSkeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
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
  const [choosing, setChoosing] = useState(false);
  const lookup = useApi(`${org}/${projectSlug}/${environmentSlug}/${serviceSlug}`, () => loadService(org, projectSlug, environmentSlug, serviceSlug));
  const { query: role, admin } = useRole(org);
  const current = lookup.data?.service.current;
  const running = current?.status === "running";
  const visible = admin ? tabs : tabs.filter((t) => t.id !== "settings");
  const requested = search.get("tab") === "builds" ? "deployments" : search.get("tab");
  const tab = visible.find((t) => t.id === requested)?.id ?? "deployments";
  const show = (id: Tab, build?: string) => window.history.replaceState(null, "", `${pathname}?${new URLSearchParams(build ? { tab: id, build } : { tab: id })}`);
  const started = (build?: string) => {
    setChoosing(false);
    show("deployments", build);
    setRound((r) => r + 1);
    lookup.reload();
  };
  const deploy = useAction(async (id: string, ref?: string) => started((await api<Build>(`/services/${id}/deploy`, { method: "POST", body: ref ? { ref } : {} })).id));
  const redeploy = useAction(async (id: string) => {
    await api(`/services/${id}/redeploy`, { method: "POST" });
    started();
  });
  usePolling(!!lookup.data && (!current || current.status === "pending" || current.status === "releasing"), lookup.reload);
  if (lookup.error) return <ErrorState error={lookup.error} retry={lookup.reload} />;
  if (role.error) return <ErrorState error={role.error} retry={role.reload} />;
  if (!lookup.data || role.loading) return <PageSkeleton />;
  const { service, environment } = lookup.data;
  const projectHref = `/${org}/${projectSlug}`;
  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-col gap-2">
        <PageHeader
          title={
            <span className="flex items-center gap-2">
              {stackIcon(service.framework) && <StackIcon id={service.framework} className="size-5" />}
              {service.name}
            </span>
          }
          description={[environment.name, ...(current ? [shortSha(current.commitSha), `deployed ${timeAgo(current.createdAt)}`] : ["not deployed yet"])].join(" · ")}
          actions={
            <>
              <StatusBadge status={current?.status ?? "not deployed"} />
              {running && service.url && (
                <a href={service.url} target="_blank" rel="noreferrer" className={buttonClasses()}>
                  Visit
                </a>
              )}
              {admin && (
                <div role="group" aria-label="Deploy" className="flex">
                  <Button
                    variant="primary"
                    pending={redeploy.pending || (deploy.pending && !choosing)}
                    onClick={() => (running ? redeploy.run(service.id) : deploy.run(service.id))}
                    className="rounded-r-none"
                  >
                    {running ? "Redeploy" : "Deploy"}
                  </Button>
                  <Menu
                    label="Deploy options"
                    popup="right-0 top-full mt-1 w-60"
                    className={buttonClasses("primary", "rounded-l-none border-l border-graphite-950/40 px-2")}
                    trigger={
                      <>
                        <Icon name="chevronDown" />
                        <span className="sr-only">More deploy options</span>
                      </>
                    }
                  >
                    {running && <MenuButton onClick={() => deploy.run(service.id)}>Deploy latest commit</MenuButton>}
                    <MenuButton onClick={() => setChoosing(true)}>Deploy a branch or commit…</MenuButton>
                  </Menu>
                </div>
              )}
            </>
          }
        />
        <FormError message={choosing ? undefined : (deploy.error ?? redeploy.error)} />
      </div>
      <Tabs items={visible} value={tab} label={`${service.name} sections`} onChange={(id) => show(id)}>
        {tab === "deployments" && (
          <DeploymentsTab
            key={round}
            service={service}
            environment={environment}
            admin={admin}
            linked={search.get("build") ?? undefined}
            onSelect={(id) => show("deployments", id)}
            onChanged={lookup.reload}
          />
        )}
        {tab === "logs" && <LogsTab service={service} />}
        {tab === "metrics" && <MetricsTab service={service} />}
        {tab === "env" && <EnvTab service={service} environment={environment} projectHref={projectHref} admin={admin} onChanged={lookup.reload} />}
        {tab === "domains" && <DomainsTab service={service} admin={admin} />}
        {tab === "settings" && <SettingsTab org={org} service={service} section={search.get("section")} projectHref={projectHref} onChanged={lookup.reload} />}
      </Tabs>
      <Dialog open={choosing} title="Deploy a branch or commit" onClose={() => setChoosing(false)}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            deploy.run(service.id, formValues(e.currentTarget).ref);
          }}
          className="flex flex-col gap-4"
        >
          <Field label="Branch or commit" hint={`Empty deploys the head of ${environment.branch}`}>
            <Input name="ref" autoFocus placeholder={environment.branch} className="font-mono" />
          </Field>
          <FormError message={deploy.error} />
          <div className="flex justify-end gap-2">
            <Button onClick={() => setChoosing(false)}>Cancel</Button>
            <Button type="submit" variant="primary" pending={deploy.pending}>
              Deploy
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
