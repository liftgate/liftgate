import type { Metadata } from "next";
import { ServiceView } from "./service";

export async function generateMetadata({ params }: PageProps<"/[org]/[project]/[environment]/[service]">): Promise<Metadata> {
  const { project, environment, service } = await params;
  return { title: `${service} · ${environment} · ${project}` };
}

export default async function ServicePage({ params }: PageProps<"/[org]/[project]/[environment]/[service]">) {
  const { org, project, environment, service } = await params;
  return <ServiceView org={org} projectSlug={project} environmentSlug={environment} serviceSlug={service} />;
}
