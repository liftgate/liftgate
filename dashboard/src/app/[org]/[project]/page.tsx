import type { Metadata } from "next";
import { Overview } from "./overview";

export async function generateMetadata({ params }: PageProps<"/[org]/[project]">): Promise<Metadata> {
  const { org, project } = await params;
  return { title: `${project} · ${org}` };
}

export default async function ProjectPage({ params }: PageProps<"/[org]/[project]">) {
  const { org, project } = await params;
  return <Overview org={org} projectSlug={project} />;
}
