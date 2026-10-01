import { redirect } from "next/navigation";
import { Projects } from "./projects";

export default async function OrgPage({ params, searchParams }: PageProps<"/[org]">) {
  const { org } = await params;
  if ((await searchParams).new === "project") redirect(`/new?org=${org}`);
  return <Projects org={org} />;
}
