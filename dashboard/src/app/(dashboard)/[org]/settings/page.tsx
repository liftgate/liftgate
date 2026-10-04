import type { Metadata } from "next";
import { Settings } from "./settings";

export async function generateMetadata({ params }: PageProps<"/[org]/settings">): Promise<Metadata> {
  const { org } = await params;
  return { title: `General · ${org}` };
}

export default async function SettingsPage({ params }: PageProps<"/[org]/settings">) {
  const { org } = await params;
  return <Settings org={org} />;
}
