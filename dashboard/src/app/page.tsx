import type { Metadata } from "next";
import { cookies } from "next/headers";
import { connection } from "next/server";
import { Landing, landingMetadata } from "@/components/landing/landing";
import { Shell } from "@/components/shell";
import { landingOn, showsLanding } from "@/lib/landing";
import { OrgChooser } from "./org-chooser";

export async function generateMetadata(): Promise<Metadata> {
  await connection();
  return landingOn(process.env.LIFTGATE_LANDING) ? landingMetadata : {};
}

export default async function Home() {
  const jar = await cookies();
  if (showsLanding(process.env.LIFTGATE_LANDING, (name) => jar.has(name))) return <Landing />;
  return (
    <Shell>
      <OrgChooser />
    </Shell>
  );
}
