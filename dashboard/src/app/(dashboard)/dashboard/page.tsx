import { OrgChooser } from "./org-chooser";

export default async function DashboardPage({ searchParams }: PageProps<"/dashboard">) {
  return <OrgChooser installed={(await searchParams).installed !== undefined} />;
}
