import type { MetadataRoute } from "next";
import { landingEnabled } from "@/lib/landing";

export default async function robots(): Promise<MetadataRoute.Robots> {
  return (await landingEnabled())
    ? { rules: { userAgent: "*", allow: "/" }, sitemap: "https://liftgate.dev/sitemap.xml" }
    : { rules: { userAgent: "*", allow: "/" } };
}
