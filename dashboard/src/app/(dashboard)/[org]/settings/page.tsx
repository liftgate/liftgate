import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/page-header";
import { Card } from "@/components/ui/card";

const sections = [
  { path: "tokens", title: "API tokens", description: "Credentials for CI and scripts, with an expiry and revocation." },
  { path: "sso", title: "SAML single sign-on", description: "Members sign in through your identity provider." },
  { path: "notifications", title: "Notifications", description: "Build failures and deployments posted to Slack, Discord or a signed webhook." },
];

export async function generateMetadata({ params }: PageProps<"/[org]/settings">): Promise<Metadata> {
  const { org } = await params;
  return { title: `Settings · ${org}` };
}

export default async function SettingsPage({ params }: PageProps<"/[org]/settings">) {
  const { org } = await params;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Settings" description="Access, sign-in and notifications for this organization." />
      <Card className="overflow-hidden">
        <ul className="divide-y divide-graphite-700">
          {sections.map((section) => (
            <li key={section.path}>
              <Link
                href={`/${org}/settings/${section.path}`}
                className="flex items-center justify-between gap-4 px-6 py-4 transition-colors hover:bg-graphite-800/50 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
              >
                <div className="min-w-0">
                  <p className="text-sm font-medium">{section.title}</p>
                  <p className="mt-1 text-sm text-graphite-400">{section.description}</p>
                </div>
                <span aria-hidden className="text-graphite-400">
                  →
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
