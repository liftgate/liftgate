import type { Metadata } from "next";
import { Invitation } from "./invitation";

export const metadata: Metadata = { title: "Invitation" };

export default async function InvitationPage({ params }: PageProps<"/account/invitations/[token]">) {
  const { token } = await params;
  return <Invitation token={token} />;
}
