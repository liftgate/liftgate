import type { Metadata } from "next";
import { cookies } from "next/headers";
import { Landing, landingMetadata } from "@/components/landing/landing";
import { Shell } from "@/components/shell";
import { landingEnabled, sessionCookies } from "@/lib/landing";
import { OrgChooser } from "./org-chooser";

export async function generateMetadata(): Promise<Metadata> {
  return (await landingEnabled()) ? landingMetadata : {};
}

export default async function Home() {
  const jar = await cookies();
  if ((await landingEnabled()) && !sessionCookies.some((name) => jar.has(name))) return <Landing />;
  return (
    <Shell>
      <OrgChooser />
    </Shell>
  );
}
