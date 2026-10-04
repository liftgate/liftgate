"use client";

import Image from "next/image";
import Link from "next/link";
import { useParams, usePathname, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useApi, useOnChange, useSignOut } from "@/lib/hooks";
import { stackIcon } from "@/lib/stacks";
import type { Environment, OperatorSummary, Organization, Project, ProjectTree, Service, User } from "@/lib/types";
import { planName } from "@/lib/util";
import { CreateOrgForm } from "./create-org-form";
import { Footer } from "./footer";
import { Icon, StackIcon } from "./icons";
import { docsUrl } from "./landing/links";
import { NavItem, navItemClasses } from "./settings-layout";
import { Badge, statusTone, type Tone } from "./ui/badge";
import { Dialog } from "./ui/dialog";
import { Input } from "./ui/input";
import { Menu, MenuButton, MenuDivider, MenuLink } from "./ui/menu";
import { Skeleton } from "./ui/skeleton";

const LAST_ORG = "liftgate:org";

export const lastOrg = (orgs: Organization[]) => orgs.find((o) => o.slug === localStorage.getItem(LAST_ORG)) ?? orgs[0];

const dots: Record<Tone, string> = { success: "bg-success", warning: "bg-warning", danger: "bg-danger", accent: "bg-accent", neutral: "bg-graphite-600" };

const triggerClasses = "flex h-10 w-full items-center gap-2 rounded-md px-2 text-sm hover:bg-graphite-800 focus-visible:outline-2 focus-visible:outline-accent";

export type Scope = ReturnType<typeof useScope>;

export function useScope() {
  const pathname = usePathname();
  const search = useSearchParams();
  const params = useParams<{ org?: string; project?: string; environment?: string; service?: string }>();
  const onNew = pathname === "/new";
  const routeOrg = params.org ?? (onNew ? search.get("org") : null) ?? undefined;
  const me = useApi<User>("/me");
  const orgs = useApi<Organization[]>("/orgs");
  const list = orgs.data;
  useOnChange(orgs.reload, !routeOrg || !list || list.some((o) => o.slug === routeOrg) ? "known" : routeOrg);
  const org = list && (list.find((o) => o.slug === routeOrg) ?? lastOrg(list));
  const project = org?.slug === routeOrg ? (params.project ?? (onNew ? search.get("project") : null) ?? undefined) : undefined;
  const tree = useApi<ProjectTree>(project && `/orgs/${org?.slug}/projects/${project}/tree`);
  useOnChange(tree.reload, pathname);
  const projects = useApi<Project[]>(project && `/orgs/${org?.slug}/projects#${project}`, () => api<Project[]>(`/orgs/${org?.slug}/projects`));
  const pending = useApi<OperatorSummary>(me.data?.operator && "/operator/summary").data?.pending;
  useEffect(() => {
    if (org && org.slug === routeOrg) localStorage.setItem(LAST_ORG, org.slug);
  }, [org, routeOrg]);
  const base = org && `/${org.slug}`;
  const current =
    pathname === "/dashboard/operator"
      ? "operator"
      : pathname.startsWith("/account")
        ? "account"
        : project
          ? pathname === `${base}/${project}/settings`
            ? "project-settings"
            : params.service
              ? `${params.environment}/${params.service}`
              : pathname === `${base}/${project}` || onNew
                ? "overview"
                : undefined
          : pathname.startsWith(`${base}/settings`)
            ? "org-settings"
            : pathname === base || onNew
              ? "projects"
              : undefined;
  const title = params.service ? `${params.service} · ${project}` : (project ?? (current === "account" ? "Account" : current === "operator" ? "Operator" : org?.name));
  return { me: me.data, orgs: list, org, project, tree: tree.data, projects: projects.data, pending, current, title };
}

function Monogram({ name, danger = false }: { name: string; danger?: boolean }) {
  return (
    <span aria-hidden className="relative flex size-6 shrink-0 items-center justify-center rounded-md bg-graphite-700 text-xs font-semibold text-white">
      {name.charAt(0).toUpperCase()}
      {danger && <span className="absolute -top-1 -right-1 size-2 rounded-full bg-danger" />}
    </span>
  );
}

export function Sidebar({ scope }: { scope: Scope }) {
  const { org, project, tree, current, me, pending } = scope;
  const base = org && `/${org.slug}`;
  return (
    <nav aria-label="Main" className="flex min-h-full flex-col p-3">
      {org && scope.orgs ? (
        <OrgSwitcher org={org} orgs={scope.orgs} prefill={me?.name ?? me?.login} />
      ) : scope.orgs ? (
        <NavItem href="/dashboard" icon="plus">
          Create organization
        </NavItem>
      ) : (
        <Skeleton className="h-10" />
      )}
      <hr className="my-2 border-graphite-700" />
      <div className="flex flex-col gap-1">
        {base && project ? (
          <>
            <Link href={base} className="flex h-8 items-center gap-2 rounded-md px-2 text-xs text-graphite-400 hover:text-white focus-visible:outline-2 focus-visible:outline-accent">
              <Icon name="back" />
              Projects
            </Link>
            <ProjectSwitcher org={org} project={tree?.project} projects={scope.projects} />
            <NavItem href={`${base}/${project}`} icon="overview" current={current === "overview"}>
              Overview
            </NavItem>
            {tree ? <Services tree={tree} href={`${base}/${project}`} current={current} /> : <Skeleton className="h-8" />}
            <NavItem href={`${base}/${project}/settings`} icon="settings" current={current === "project-settings"}>
              Settings
            </NavItem>
          </>
        ) : base ? (
          <>
            <NavItem href={base} icon="projects" current={current === "projects"}>
              Projects
            </NavItem>
            <NavItem href={`${base}/settings`} icon="settings" current={current === "org-settings"}>
              Settings
            </NavItem>
          </>
        ) : (
          !scope.orgs && <Skeleton className="h-16" />
        )}
      </div>
      <div className="mt-auto flex flex-col gap-1 pt-6">
        <hr className="mb-1 border-graphite-700" />
        {me?.operator && (
          <NavItem href="/dashboard/operator" icon="operator" current={current === "operator"}>
            Operator
            {!!pending && (
              <span className="ml-auto">
                <Badge tone="warning">
                  {pending}
                  <span className="sr-only"> pending</span>
                </Badge>
              </span>
            )}
          </NavItem>
        )}
        <a href={docsUrl("getting-started")} target="_blank" rel="noreferrer" className={`${navItemClasses} text-graphite-400 hover:text-white`}>
          <Icon name="docs" />
          Docs
          <Icon name="external" className="ml-auto size-3" />
        </a>
        {me ? <UserMenu user={me} current={current === "account"} /> : <Skeleton className="h-10" />}
        <Footer sidebar />
      </div>
    </nav>
  );
}

function OrgSwitcher({ org, orgs, prefill }: { org: Organization; orgs: Organization[]; prefill?: string }) {
  const [creating, setCreating] = useState(false);
  return (
    <>
      <Menu
        label="Organizations"
        className={triggerClasses}
        trigger={
          <>
            <Monogram name={org.name} danger={!!org.suspendedAt} />
            <span className="min-w-0 flex-1 truncate text-left font-medium">
              <span className="sr-only">Organization: </span>
              {org.name}
              {org.suspendedAt && <span className="sr-only">, suspended</span>}
            </span>
            <Badge>{planName(org.plan)}</Badge>
            <Icon name="selector" className="size-4 text-graphite-400" />
          </>
        }
      >
        {orgs.map((o) => (
          <MenuLink key={o.slug} href={`/${o.slug}`} checked={o.slug === org.slug}>
            <span className="min-w-0 flex-1 truncate">{o.name}</span>
            {o.slug === org.slug && <Icon name="check" />}
          </MenuLink>
        ))}
        <MenuButton onClick={() => setCreating(true)}>
          <Icon name="plus" />
          Create organization
        </MenuButton>
        <MenuDivider />
        <MenuLink href={`/${org.slug}/settings`}>
          <Icon name="settings" />
          Organization settings
        </MenuLink>
      </Menu>
      <Dialog open={creating} title="Create organization" onClose={() => setCreating(false)}>
        <CreateOrgForm prefill={prefill} onCreated={() => setCreating(false)} onCancel={() => setCreating(false)} />
      </Dialog>
    </>
  );
}

function ProjectSwitcher({ org, project, projects }: { org: Organization; project?: Project; projects?: Project[] }) {
  const [filter, setFilter] = useState("");
  const shown = projects?.filter((p) => `${p.name} ${p.slug}`.toLowerCase().includes(filter.trim().toLowerCase()));
  if (!project) return <Skeleton className="h-10" />;
  return (
    <Menu
      label="Projects"
      className={triggerClasses}
      trigger={
        <>
          <Monogram name={project.name} />
          <span className="min-w-0 flex-1 truncate text-left font-medium">
            <span className="sr-only">Project: </span>
            {project.name}
          </span>
          <Icon name="chevronDown" className="size-4 text-graphite-400" />
        </>
      }
      top={
        projects &&
        projects.length > 8 && <Input aria-label="Filter projects" placeholder="Filter projects" value={filter} onChange={(e) => setFilter(e.target.value)} className="w-full" />
      }
    >
      {shown
        ? shown.map((p) => (
            <MenuLink key={p.id} href={`/${org.slug}/${p.slug}`} checked={p.id === project.id}>
              <span className="min-w-0 flex-1 truncate">{p.name}</span>
              {p.id === project.id && <Icon name="check" />}
            </MenuLink>
          ))
        : [0, 1].map((i) => <Skeleton key={i} className="m-1 h-6" />)}
      <MenuDivider />
      <MenuLink href={`/${org.slug}`}>
        <Icon name="projects" />
        All projects
      </MenuLink>
      {(org.role === "owner" || org.role === "admin") && (
        <MenuLink href={`/new?org=${org.slug}`}>
          <Icon name="plus" />
          Import repository
        </MenuLink>
      )}
    </Menu>
  );
}

function Services({ tree, href, current }: { tree: ProjectTree; href: string; current?: string }) {
  const environments = tree.environments.filter((e) => e.pullRequest === null).sort((a, b) => Number(b.kind === "production") - Number(a.kind === "production"));
  const previews = tree.environments.filter((e) => e.pullRequest !== null);
  const rows = (environment: Environment) =>
    tree.services
      .filter((s) => s.environmentId === environment.id)
      .map((service) => <ServiceItem key={service.id} service={service} href={`${href}/${environment.slug}/${service.slug}`} current={current === `${environment.slug}/${service.slug}`} />);
  const group = (environment: Environment) => (
    <div key={environment.id} className="flex flex-col gap-1">
      <p className="truncate px-2 pt-2 text-xs font-medium text-graphite-400">{environment.name}</p>
      {rows(environment)}
    </div>
  );
  return (
    <>
      {environments.length === 1 ? rows(environments[0]) : environments.map(group)}
      {previews.length > 0 && (
        <details open={previews.some((e) => current?.startsWith(`${e.slug}/`))} className="group">
          <summary className={`${navItemClasses} cursor-pointer list-none text-graphite-400 hover:text-white [&::-webkit-details-marker]:hidden`}>
            <Icon name="chevronRight" className="size-4 transition-transform group-open:rotate-90" />
            Previews ({previews.length})
          </summary>
          <div className="flex flex-col gap-1">{previews.map(group)}</div>
        </details>
      )}
    </>
  );
}

function ServiceItem({ service, href, current }: { service: Service; href: string; current: boolean }) {
  const status = service.current?.status.replace("_", " ") ?? "not deployed";
  return (
    <NavItem href={href} current={current}>
      <span aria-hidden className={`size-2 shrink-0 rounded-full ${dots[statusTone(status)]}`} />
      {stackIcon(service.framework) && <StackIcon id={service.framework} />}
      <span className="min-w-0 truncate">{service.name}</span>
      <span className="sr-only">, {status}</span>
    </NavItem>
  );
}

function UserMenu({ user, current }: { user: User; current: boolean }) {
  const signOut = useSignOut();
  return (
    <Menu
      label="Account"
      current={current}
      popup="inset-x-0 bottom-full mb-1"
      className={`${triggerClasses} ${current ? "bg-graphite-800 font-medium" : ""}`}
      trigger={
        <>
          {user.avatarUrl ? (
            <Image src={user.avatarUrl} alt="" width={24} height={24} unoptimized className="size-6 shrink-0 rounded-md" />
          ) : (
            <Monogram name={user.login} />
          )}
          <span className="min-w-0 flex-1 truncate text-left">
            <span className="sr-only">Account: </span>
            {user.login}
          </span>
          <Icon name="more" className="size-4 text-graphite-400" />
        </>
      }
    >
      <MenuLink href="/account">
        <Icon name="account" />
        Account settings
      </MenuLink>
      <MenuButton onClick={() => signOut.run()}>
        <Icon name="signOut" />
        Sign out
      </MenuButton>
    </Menu>
  );
}
