import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { cache } from "react";
import { apiStatus } from "@/lib/api-status";

const missing = cache(async (org: string) => (await apiStatus(`/orgs/${encodeURIComponent(org)}`)) === 404);

export async function generateMetadata({ params }: LayoutProps<"/[org]">): Promise<Metadata> {
  const { org } = await params;
  return (await missing(org))
    ? { title: { default: "Page not found", template: "Page not found · Liftgate" } }
    : { title: { default: org, template: "%s · Liftgate" } };
}

export default async function OrgLayout({ params, children }: LayoutProps<"/[org]">) {
  if (await missing((await params).org)) notFound();
  return children;
}
