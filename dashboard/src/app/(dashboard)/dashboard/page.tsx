import type { Metadata } from "next";
import { OrgChooser } from "./org-chooser";

export const metadata: Metadata = { title: "Dashboard" };

export default async function DashboardPage({ searchParams }: PageProps<"/dashboard">) {
  return <OrgChooser installed={(await searchParams).installed !== undefined} />;
}
