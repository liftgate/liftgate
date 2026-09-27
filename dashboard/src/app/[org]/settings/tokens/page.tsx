import type { Metadata } from "next";
import { Tokens } from "./tokens";

export async function generateMetadata({ params }: PageProps<"/[org]/settings/tokens">): Promise<Metadata> {
  const { org } = await params;
  return { title: `API tokens · ${org}` };
}

export default async function TokensPage({ params }: PageProps<"/[org]/settings/tokens">) {
  const { org } = await params;
  return <Tokens org={org} />;
}
