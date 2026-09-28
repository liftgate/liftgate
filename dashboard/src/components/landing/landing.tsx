import type { Metadata } from "next";
import Link from "next/link";
import { Footer } from "@/components/footer";
import { Nav } from "@/components/nav";
import { buttonClasses } from "@/components/ui/button";
import { Closing } from "./closing";
import { CloudOrYours } from "./cloud-or-yours";
import { demo } from "./demo";
import { Hero } from "./hero";
import { HowItWorks } from "./how-it-works";
import { LinkPending } from "./link-pending";
import { repoUrl } from "./links";
import { OneProject } from "./one-project";
import { Security } from "./security";
import { SelfHost } from "./self-host";
import { Stacks } from "./stacks";
import { navLink, signInLink } from "./styles";

const title = "Liftgate: open-source hosting for full-stack apps";
const description = "Push to GitHub and Liftgate builds and runs your web apps, APIs, workers and cron jobs. Use Liftgate Cloud or your own Kubernetes.";

export const landingMetadata: Metadata = {
  metadataBase: new URL("https://liftgate.dev"),
  title: { absolute: title },
  description,
  alternates: { canonical: "/" },
  openGraph: {
    type: "website",
    url: "/",
    siteName: "Liftgate",
    title,
    description,
    images: [{ url: "/og.jpg", width: 1200, height: 630, alt: "The Liftgate logo above the line Full-stack hosting. Open source." }],
  },
  twitter: { card: "summary_large_image" },
};

const footerLinks = [
  { label: "Source code", href: repoUrl },
  { label: "Install guide", href: `${repoUrl}#self-hosting` },
  { label: "AGPL-3.0", href: `${repoUrl}/blob/main/LICENSE` },
  { label: "Security policy", href: `${repoUrl}/blob/main/SECURITY.md` },
];

export function Landing() {
  const { builds, deployments } = demo();
  return (
    <>
      <a href="#main" className={buttonClasses("secondary", "fixed top-3 left-6 z-20 -translate-y-16 focus:translate-y-0")}>
        Skip to content
      </a>
      <div className="sticky top-0 z-10">
        <Nav
          actions={
            <>
              <nav aria-label="Site" className="hidden items-center gap-6 md:flex">
                <a href="#how-it-works" className={navLink}>
                  How it works
                </a>
                <a href="#self-hosting" className={navLink}>
                  Self-host
                </a>
                <a href={repoUrl} target="_blank" rel="noreferrer" className={navLink}>
                  Source code
                </a>
              </nav>
              <Link href="/login" className={signInLink}>
                <LinkPending />
                Sign in
              </Link>
              <Link href="/login" className={buttonClasses("primary")}>
                <LinkPending />
                Request access
              </Link>
            </>
          }
        />
      </div>
      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-6 pb-24 md:pb-40 xl:pb-52">
        <Hero />
        <Stacks />
        <HowItWorks builds={builds} deployments={deployments} />
        <OneProject />
        <CloudOrYours />
        <Security />
        <SelfHost />
        <Closing />
      </main>
      <Footer links={footerLinks} />
    </>
  );
}
