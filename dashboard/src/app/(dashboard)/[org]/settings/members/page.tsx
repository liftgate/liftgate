import type { Metadata } from "next";
import { Members } from "./members";

export async function generateMetadata({ params }: PageProps<"/[org]/settings/members">): Promise<Metadata> {
  const { org } = await params;
  return { title: `Members · ${org}` };
}

export default async function MembersPage({ params }: PageProps<"/[org]/settings/members">) {
  const { org } = await params;
  return <Members org={org} />;
}
