import type { Metadata } from "next";
import { ServiceView } from "./service";

export async function generateMetadata({ params }: PageProps<"/[org]/[project]/[service]">): Promise<Metadata> {
  const { project, service } = await params;
  return { title: `${service} · ${project}` };
}

export default async function ServicePage({ params }: PageProps<"/[org]/[project]/[service]">) {
  const { org, project, service } = await params;
  return <ServiceView org={org} projectSlug={project} serviceSlug={service} />;
}
