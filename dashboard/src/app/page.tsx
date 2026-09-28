import type { Metadata } from "next";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { connection } from "next/server";
import { Landing, landingMetadata } from "@/components/landing/landing";
import { landingFor, landingOn } from "@/lib/landing";

export async function generateMetadata(): Promise<Metadata> {
  await connection();
  return landingOn(process.env.LIFTGATE_LANDING) ? landingMetadata : {};
}

export default async function Home() {
  const jar = await cookies();
  const landing = landingFor(process.env.LIFTGATE_LANDING, (name) => jar.has(name));
  if (!landing) redirect("/dashboard");
  return <Landing signedIn={landing.signedIn} />;
}
