import type { Metadata } from "next";
import { ProjectSettings } from "./project-settings";

export async function generateMetadata({ params }: PageProps<"/[org]/[project]/settings">): Promise<Metadata> {
  const { org, project } = await params;
  return { title: `Settings · ${project} · ${org}` };
}

export default async function ProjectSettingsPage({ params }: PageProps<"/[org]/[project]/settings">) {
  const { org, project } = await params;
  return <ProjectSettings org={org} projectSlug={project} />;
}
