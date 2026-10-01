import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { Overview } from "./overview";

export async function generateMetadata({ params }: PageProps<"/[org]/[project]">): Promise<Metadata> {
  const { org, project } = await params;
  return { title: `${project} · ${org}` };
}

export default async function ProjectPage({ params, searchParams }: PageProps<"/[org]/[project]">) {
  const { org, project } = await params;
  if ((await searchParams).new === "service") redirect(`/new?org=${org}&project=${project}`);
  return <Overview org={org} projectSlug={project} />;
}
