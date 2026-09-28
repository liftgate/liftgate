import type { Metadata } from "next";
import { Notifications } from "./notifications";

export async function generateMetadata({ params }: PageProps<"/[org]/settings/notifications">): Promise<Metadata> {
  const { org } = await params;
  return { title: `Notifications · ${org}` };
}

export default async function NotificationsPage({ params }: PageProps<"/[org]/settings/notifications">) {
  const { org } = await params;
  return <Notifications org={org} />;
}
