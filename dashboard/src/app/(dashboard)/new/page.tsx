import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { Configure } from "./configure";
import { ImportRepository } from "./import-repository";

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export async function generateMetadata({ searchParams }: PageProps<"/new">): Promise<Metadata> {
  const { repo, project } = await searchParams;
  return { title: repo ? `Deploy ${first(repo)}` : project ? "Add a service" : "Import a repository" };
}

export default async function NewPage({ searchParams }: PageProps<"/new">) {
  const search = await searchParams;
  const org = first(search.org);
  if (!org) redirect("/dashboard");
  const repo = first(search.repo);
  const project = first(search.project);
  if (repo || project) return <Configure key={`${repo}:${project}`} org={org} repo={repo} projectSlug={project} environmentSlug={first(search.environment)} />;
  return <ImportRepository org={org} />;
}
