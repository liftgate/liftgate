import type { Metadata } from "next";
import { headers } from "next/headers";
import { notFound } from "next/navigation";
import { cache } from "react";

const missing = cache(async (org: string) => {
  const api = process.env.LIFTGATE_API_URL;
  if (!api) return false;
  const cookie = (await headers()).get("cookie") ?? "";
  const status = await fetch(`${api}/api/v1/orgs/${encodeURIComponent(org)}`, { headers: { cookie }, signal: AbortSignal.timeout(3000) })
    .then((res) => res.text().then(() => res.status))
    .catch(() => 0);
  return status === 404;
});

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
