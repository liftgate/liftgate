import type { Metadata } from "next";
import { AuditLog } from "./audit-log";

export async function generateMetadata({ params }: PageProps<"/[org]/settings/audit">): Promise<Metadata> {
  const { org } = await params;
  return { title: `Audit log · ${org}` };
}

export default async function AuditPage({ params }: PageProps<"/[org]/settings/audit">) {
  const { org } = await params;
  return <AuditLog key={org} org={org} />;
}
