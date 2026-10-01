"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, describe } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuthProviders, DetectedService, Detection, Environment, Project, ProjectTree, Service, Usage } from "@/lib/types";
import { classifyVariable, detectedRow, fitPlan, formValues, mergeDotenv, planName, platformHost, sameApp, servesHttp, shortSha, type EnvRow } from "@/lib/util";
import { DetectedApp, directoryOf } from "@/components/detected-app";
import { EnvRows, useEnvPaste } from "@/components/env-rows";
import { NameSlugFields, toSlug } from "@/components/name-slug-fields";
import { PageHeader } from "@/components/page-header";
import { ProviderGlyph, ProviderLink } from "@/components/provider";
import { toSpec } from "@/components/service-form";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState, ErrorState } from "@/components/ui/empty-state";
import { Field, FormError } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";

type App = { key: string; detected: DetectedService | null };

const STILL_READING_MS = 5000;
const production = (environments: Environment[]) => environments.find((e) => e.kind === "production") ?? environments[0];
const rank = (row: EnvRow) => (row.required ? 0 : classifyVariable(row.name).buildTime ? 1 : 2);
const detectedRows = (detected: DetectedService | null): EnvRow[] => (detected?.variables ?? []).map(detectedRow).sort((a, b) => rank(a) - rank(b));
const elsewhere = (path: string): DetectedService => ({
  selected: true,
  framework: null,
  packageManager: null,
  builder: "railpack",
  spec: { slug: toSlug(path.split("/").pop() ?? ""), name: path.split("/").pop() ?? "", kind: "web", rootDir: path, buildStrategy: "auto", dockerfilePath: "Dockerfile", port: null, replicas: 1, cpuMillis: 500, memoryMb: 512, cronSchedule: null, startCommand: null, healthCheckPath: null, watchPaths: [], volume: null },
  defaults: { build: null, start: null },
  evidence: "",
  warnings: [],
  healthHint: null,
  variables: [],
});

export function Configure({ org, repo, projectSlug, environmentSlug }: { org: string; repo?: string; projectSlug?: string; environmentSlug?: string }) {
  const router = useRouter();
  const providers = useApi<AuthProviders>("/auth/providers").data;
  const deployDomain = providers?.deployDomain;
  const usage = useApi<Usage>(`/orgs/${org}/usage`).data;
  const tree = useApi<ProjectTree>(!repo && projectSlug && `/orgs/${org}/projects/${projectSlug}/tree`);
  const [environmentId, setEnvironmentId] = useState<string>();
  const environments = tree.data?.environments.filter((e) => e.pullRequest === null) ?? [];
  const target = environments.find((e) => e.id === environmentId) ?? environments.find((e) => e.slug === environmentSlug) ?? production(environments);
  const repoName = repo?.split("/")[1] ?? "";
  const detection = useApi<Detection>(
    repo ? `/me/github/repositories/detect?repo=${encodeURIComponent(repo)}` : tree.data && target && `/projects/${tree.data.project.id}/detect?ref=${encodeURIComponent(target.branch)}`,
  );
  const [late, setLate] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setLate(true), STILL_READING_MS);
    return () => clearTimeout(timer);
  }, []);
  const [named, setNamed] = useState({ name: repoName, slug: toSlug(repoName) });
  const [project, setProject] = useState<Project>();
  const [swaps, setSwaps] = useState<Record<string, DetectedService>>({});
  const [toggled, setToggled] = useState<Record<string, boolean>>({});
  const [edited, setEdited] = useState<Record<string, EnvRow[]>>({});
  const [focused, setFocused] = useState<string>();
  const [created, setCreated] = useState<Record<string, Service & { buildId?: string }>>({});
  const [failure, setFailure] = useState<{ key: string; message: string; field?: string }>();
  const [step, setStep] = useState<string>();
  const forms = useRef(new Map<string, HTMLFormElement>());

  const owner = project ?? tree.data?.project;
  const existing = tree.data?.services.filter((s) => s.environmentId === target?.id) ?? [];
  const found = (detection.data?.services ?? []).filter((d) => !existing.some((s) => sameApp(s, d.spec)));
  const reading = !detection.data && !detection.error;
  const ready = !reading || (late && (!!repo || !!tree.data));
  const apps: App[] = (found.length ? found.map((detected, i) => ({ key: String(i), detected })) : ready ? [{ key: "0", detected: null }] : []).map((app) =>
    app.key in swaps ? { ...app, detected: swaps[app.key] } : app,
  );
  const multi = apps.length > 1;
  const preferred = apps.filter((app) => app.detected?.selected ?? true).map((app) => app.key);
  const fit = usage ? fitPlan(preferred.length, usage) : { count: preferred.length, cpuMillis: 500, memoryMb: 512 };
  const checked = (key: string) => !multi || (toggled[key] ?? preferred.slice(0, fit.count).includes(key));
  const chosen = apps.filter((app) => checked(app.key));
  const rowsFor = (app: App) => edited[app.key] ?? detectedRows(app.detected);
  const setRowsFor = (key: string) => (rows: EnvRow[]) => setEdited((all) => ({ ...all, [key]: rows }));
  const groups = multi ? chosen : apps;
  const pasteTarget = groups.find((app) => app.key === focused) ?? groups[0];
  const paste = useEnvPaste(pasteTarget ? rowsFor(pasteTarget) : [], (rows) => pasteTarget && setRowsFor(pasteTarget.key)(rows));
  const rootApp = apps.length === 1 && (apps[0].detected?.spec.rootDir ?? "/") === "/";
  const prefillFor = (app: App) => {
    if (repo && rootApp) return named;
    const name = app.detected?.spec.name ?? (repo ? repoName : "web");
    return { name, slug: toSlug(name) };
  };
  const environmentLabel = repo ? "production" : (target?.slug ?? "production");
  const hostFor = (projectLabel: string) => (slug: string) =>
    deployDomain ? platformHost({ service: slug, environment: environmentLabel, project: projectLabel, org }, deployDomain) : undefined;
  const primary = chosen.find((app) => servesHttp(app.detected?.spec.kind ?? "web"));

  const deploy = useAction(async () => {
    if (chosen.some((app) => forms.current.get(app.key)?.reportValidity() === false)) return;
    setFailure(undefined);
    let parent = owner;
    if (!parent && repo) {
      setStep("Creating project…");
      parent = await api<Project>(`/orgs/${org}/projects`, { method: "POST", body: { slug: named.slug, name: named.name.trim(), repoFullName: repo } });
      setProject(parent);
    }
    const environment = parent && (repo ? production(await api<Environment[]>(`/projects/${parent.id}/environments`)) : target);
    if (!parent) return;
    if (!environment) throw new Error("This project has no environment to deploy to.");
    const done = { ...created };
    for (const app of chosen) {
      const form = forms.current.get(app.key);
      if (done[app.key] || !form) continue;
      const spec = toSpec(formValues(form));
      const env = rowsFor(app)
        .filter((row) => row.name && row.value)
        .map(({ name, value, secret }) => ({ name, value, secret }));
      setStep(`Adding ${spec.name}…`);
      try {
        done[app.key] = await api<Service & { buildId?: string }>(`/environments/${environment.id}/services?deploy=true`, {
          method: "POST",
          body: { ...spec, framework: app.detected?.framework?.id ?? null, ...(env.length ? { env } : {}) },
        });
      } catch (e) {
        setFailure({ key: app.key, message: describe(e), field: e instanceof ApiError ? e.field : undefined });
        return;
      }
      setCreated({ ...done });
    }
    setStep("Starting build…");
    const only = chosen.length === 1 ? done[chosen[0].key] : undefined;
    router.push(only ? `/${org}/${parent.slug}/${environment.slug}/${only.slug}${only.buildId ? `?tab=builds&build=${only.buildId}` : ""}` : `/${org}/${parent.slug}`);
  });

  if (detection.error?.code === "github_not_connected")
    return (
      <EmptyState
        title="Connect GitHub to import a repository"
        description="Liftgate reads the repository through your GitHub connection."
        action={
          <ProviderLink provider="github" intent="connect" next={`/new?org=${org}&repo=${repo}`}>
            Connect GitHub
          </ProviderLink>
        }
      />
    );
  const failed = repo ? detection.error : tree.error;
  if (failed) return <ErrorState error={failed} retry={repo ? detection.reload : tree.reload} />;

  const commit = detection.data?.commit;
  const warnings = detection.error ? [`Couldn't read ${owner?.repoFullName}: ${detection.error.message}. Railpack will still detect the stack during the build.`] : (detection.data?.warnings ?? []);
  const names = groups.flatMap(rowsFor);
  const sources = [...new Set(groups.flatMap((app) => app.detected?.variables.map((v) => v.source) ?? []))];
  const filled = names.filter((row) => row.value).length;
  const requiredEmpty = names.filter((row) => row.required && !row.value).length;
  const room = fit.count < preferred.length && usage && `Your ${planName(usage.plan)} plan has room for ${fit.count} more service${fit.count === 1 ? "" : "s"}.`;
  const copyToAll = (from: App) => {
    const vars = rowsFor(from).flatMap((row) => (row.name && row.value ? [{ name: row.name, value: row.value }] : []));
    setEdited((all) => ({ ...all, ...Object.fromEntries(groups.filter((app) => app.key !== from.key).map((app) => [app.key, mergeDotenv(rowsFor(app), vars).rows])) }));
  };
  const addVariable = (
    <Button onClick={() => pasteTarget && setRowsFor(pasteTarget.key)([...rowsFor(pasteTarget), { name: "", value: "", secret: false }])}>Add variable</Button>
  );
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
      <div className="flex flex-col gap-2">
        <Link href={repo ? `/new?org=${org}` : `/${org}/${projectSlug}`} className="flex w-fit items-center gap-1 rounded-sm text-sm text-graphite-400 hover:text-white focus-visible:outline-2 focus-visible:outline-accent">
          <svg viewBox="0 0 16 16" aria-hidden className="size-4">
            <path d="M10 4l-4 4 4 4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          {repo ? "Choose another repository" : `Back to ${owner?.name ?? projectSlug}`}
        </Link>
        <PageHeader
          title={
            repo ? (
              <>
                Deploy <span className="font-mono">{repo}</span>
              </>
            ) : (
              `Add a service to ${owner?.name ?? projectSlug}`
            )
          }
          description={
            commit ? (
              <span className="flex min-w-0 items-center gap-2">
                <ProviderGlyph provider="github" />
                <span className="font-mono">{detection.data?.ref}</span>·<span className="font-mono">{shortSha(commit.sha)}</span>
                {commit.message && (
                  <>
                    ·<span className="truncate">{commit.message}</span>
                  </>
                )}
              </span>
            ) : (
              reading && <Skeleton className="h-4 w-64" />
            )
          }
        />
      </div>

      {repo && (
        <Card className="p-6 max-sm:p-4">
          {project ? (
            <p className="text-sm text-graphite-200">
              Project <span className="font-medium text-white">{project.name}</span> is created. Deploy adds the apps that are still missing.
            </p>
          ) : (
            <NameSlugFields
              compact
              label="Project name"
              prefill={repoName}
              editLabel="Edit URL"
              onChange={setNamed}
              errorAt={(field) => (deploy.field === field ? deploy.error : undefined)}
              preview={(slug) => {
                const host = primary && hostFor(slug)(rootApp ? slug : (primary.detected?.spec.slug ?? slug));
                return host ? (
                  <span className="font-mono text-graphite-200">https://{host}</span>
                ) : (
                  <>
                    URL name <span className="font-mono text-graphite-200">{slug}</span>
                  </>
                );
              }}
            />
          )}
        </Card>
      )}

      {!repo && environments.length > 1 && (
        <Field label="Environment">
          <Select value={target?.id} onChange={(e) => setEnvironmentId(e.target.value)} className="sm:max-w-xs">
            {environments.map((environment) => (
              <option key={environment.id} value={environment.id}>
                {environment.name}
              </option>
            ))}
          </Select>
        </Field>
      )}

      <Card className="flex flex-col gap-6 p-6 max-sm:p-4">
        {multi && <h2 className="text-base font-medium">{apps.length} apps found</h2>}
        {warnings.map((warning) => (
          <p key={warning} role="status" className="text-sm text-warning">
            {warning}
          </p>
        ))}
        {ready ? (
          apps.map((app) => (
            <DetectedApp
              key={`${app.key}:${directoryOf(app.detected)}:${app.detected?.framework?.id ?? ""}`}
              detected={app.detected}
              directories={detection.data?.directories ?? []}
              multi={multi}
              checked={checked(app.key)}
              onCheck={(value) => setToggled((all) => ({ ...all, [app.key]: value }))}
              prefill={prefillFor(app)}
              hostFor={hostFor(owner?.slug ?? named.slug)}
              showUrl={multi || !repo}
              resources={{ cpuMillis: fit.cpuMillis, memoryMb: fit.memoryMb }}
              failure={failure?.key === app.key ? failure : undefined}
              formRef={(form) => (form ? forms.current.set(app.key, form) : forms.current.delete(app.key))}
              onSwap={(path) =>
                setSwaps((all) => ({ ...all, [app.key]: detection.data?.services.find((d) => directoryOf(d) === path) ?? elsewhere(path) }))
              }
            />
          ))
        ) : (
          <div aria-busy className="flex items-center gap-4">
            <Skeleton className="size-10" />
            <div className="flex flex-1 flex-col gap-2">
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-3 w-64" />
            </div>
          </div>
        )}
        {room && <p className="text-sm text-graphite-400">{room}</p>}
      </Card>

      <Card>
        <CardHeader
          title={names.length ? `${names.length} variable${names.length === 1 ? "" : "s"}${sources.length ? ` from ${sources.join(", ")}` : ""}` : "Environment variables (optional)"}
          divided={!!names.length || !!paste.panel}
          actions={
            ready && (
              <>
                {addVariable}
                {paste.pasteButton}
                {paste.importButton}
              </>
            )
          }
        />
        {paste.panel}
        {ready ? (
          groups.map((app) => (
            <div key={app.key} onFocusCapture={() => setFocused(app.key)}>
              {multi && rowsFor(app).length > 0 && (
                <div className="flex min-h-12 items-center justify-between gap-2 border-b border-graphite-700 px-6 max-sm:px-4">
                  <span className="font-mono text-xs text-graphite-200">{prefillFor(app).name}</span>
                  {rowsFor(app).some((row) => row.value) && (
                    <Button variant="ghost" onClick={() => copyToAll(app)}>
                      Copy to all apps
                    </Button>
                  )}
                </div>
              )}
              <EnvRows rows={rowsFor(app)} onChange={setRowsFor(app.key)} databaseHref={!repo && providers?.storage ? `/${org}/${projectSlug}` : undefined} />
            </div>
          ))
        ) : (
          <div aria-busy className="flex flex-col gap-2 px-6 py-4">
            <Skeleton className="h-8" />
            <Skeleton className="h-8" />
          </div>
        )}
        {names.length > 0 && (
          <p className="border-t border-graphite-700 px-6 py-3 text-xs text-graphite-400 max-sm:px-4">
            {filled} of {names.length} filled. Empty ones are skipped. <span className="font-mono text-graphite-200">PORT</span> is provided by Liftgate.
          </p>
        )}
      </Card>

      <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
        <div className="flex min-w-0 flex-col gap-1 text-sm sm:mr-auto">
          {late && reading && <p className="text-graphite-400">Still reading {repo ?? owner?.repoFullName}. Railpack will detect the stack during the build.</p>}
          {chosen.length > 1 && usage?.limits.concurrentBuilds === 1 && <p className="text-graphite-400">Builds run one at a time on the {planName(usage.plan)} plan.</p>}
          {requiredEmpty > 0 && (
            <p className="text-warning">
              {requiredEmpty} required variable{requiredEmpty === 1 ? " is" : "s are"} empty.
            </p>
          )}
          <FormError message={deploy.field === "name" || deploy.field === "slug" ? undefined : deploy.error} />
        </div>
        <Button variant="primary" size="lg" pending={deploy.pending} disabled={!ready || chosen.length === 0 || (!repo && !target)} onClick={() => deploy.run()} className="max-sm:w-full">
          {deploy.pending ? (step ?? "Deploying…") : !ready ? "Reading repository…" : chosen.length > 1 ? `Deploy ${chosen.length} apps` : "Deploy"}
        </Button>
      </div>
    </div>
  );
}
