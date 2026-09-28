import type { MetadataRoute } from "next";
import { landingEnabled } from "@/lib/landing";

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  return (await landingEnabled()) ? [{ url: "https://liftgate.dev/" }] : [];
}
