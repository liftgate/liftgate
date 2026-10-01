"use client";

import { redirect } from "next/navigation";
import { useApi } from "@/lib/hooks";
import type { Organization, User } from "@/lib/types";
import { CreateOrgForm } from "@/components/create-org-form";
import { PageHeader } from "@/components/page-header";
import { Card, CardHeader } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/empty-state";
import { PageSkeleton } from "@/components/ui/skeleton";

export function OrgChooser({ installed }: { installed: boolean }) {
  const me = useApi<User>("/me");
  const orgs = useApi<Organization[]>("/orgs");
  const failed = me.error ?? orgs.error;
  if (failed) return <ErrorState error={failed} retry={me.error ? me.reload : orgs.reload} />;
  if (!me.data || !orgs.data) return <PageSkeleton />;
  if (me.data.status === "pending")
    return (
      <Card className="mx-auto mt-16 w-full max-w-lg p-6">
        <PageHeader
          title="Your account is waiting for approval"
          description="The operator of this Liftgate instance approves new accounts by hand. Once yours is approved, this page lets you create your first organization."
        />
      </Card>
    );
  if (orgs.data[0]) redirect(installed ? `/new?org=${orgs.data[0].slug}` : `/${orgs.data[0].slug}`);
  return (
    <Card className="mx-auto mt-16 w-full max-w-lg">
      <CardHeader title="Create your organization" description="Projects and members belong to an organization." />
      <CreateOrgForm className="p-6" prefill={me.data.name ?? me.data.login} />
    </Card>
  );
}
