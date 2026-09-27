import { redirect } from "next/navigation";

export default async function LegacyServicePage({ params }: PageProps<"/[org]/[project]/[environment]">) {
  const { org, project, environment } = await params;
  redirect(`/${org}/${project}/production/${environment}`);
}
