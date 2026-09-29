import { Projects } from "./projects";

export default async function OrgPage({ params, searchParams }: PageProps<"/[org]">) {
  const { org } = await params;
  return <Projects org={org} opening={(await searchParams).new === "project"} />;
}
