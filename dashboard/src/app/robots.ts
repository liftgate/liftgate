import type { MetadataRoute } from "next";
import { connection } from "next/server";
import { landingOn } from "@/lib/landing";

export default async function robots(): Promise<MetadataRoute.Robots> {
  await connection();
  return landingOn(process.env.LIFTGATE_LANDING)
    ? { rules: { userAgent: "*", allow: "/" }, sitemap: "https://liftgate.dev/sitemap.xml" }
    : { rules: { userAgent: "*", allow: "/" } };
}
