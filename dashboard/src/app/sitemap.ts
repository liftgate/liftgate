import type { MetadataRoute } from "next";
import { connection } from "next/server";
import { landingOn } from "@/lib/landing";

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  await connection();
  return landingOn(process.env.LIFTGATE_LANDING) ? [{ url: "https://liftgate.dev/" }] : [];
}
