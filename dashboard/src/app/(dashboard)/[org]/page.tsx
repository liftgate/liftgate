import type { Metadata } from "next";
import { Projects } from "./projects";

export async function generateMetadata({ params }: PageProps<"/[org]">): Promise<Metadata> {
  const { org } = await params;
  return { title: org };
}

export default async function OrgPage({ params, searchParams }: PageProps<"/[org]">) {
  const { org } = await params;
  return <Projects org={org} opening={(await searchParams).new === "project"} />;
}
