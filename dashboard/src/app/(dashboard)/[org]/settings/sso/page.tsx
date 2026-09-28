import type { Metadata } from "next";
import { SsoSettings } from "./sso-settings";

export async function generateMetadata({ params }: PageProps<"/[org]/settings/sso">): Promise<Metadata> {
  const { org } = await params;
  return { title: `SAML SSO · ${org}` };
}

export default async function SsoSettingsPage({ params }: PageProps<"/[org]/settings/sso">) {
  const { org } = await params;
  return <SsoSettings org={org} />;
}
