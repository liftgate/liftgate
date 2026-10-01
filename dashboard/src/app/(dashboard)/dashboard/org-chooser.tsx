"use client";

import { redirect } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuthProviders, Organization, User } from "@/lib/types";
import { Consent } from "@/components/consent";
import { CreateOrgForm } from "@/components/create-org-form";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { PageSkeleton, Skeleton } from "@/components/ui/skeleton";

export function OrgChooser({ installed }: { installed: boolean }) {
  const me = useApi<User>("/me");
  const orgs = useApi<Organization[]>("/orgs");
  const providers = useApi<AuthProviders>(me.data?.termsPending && "/auth/providers");
  const [accepted, setAccepted] = useState(false);
  const accept = useAction(async () => {
    await api("/me/terms", { method: "POST" });
    setAccepted(true);
  });
  const failed = me.error ?? orgs.error;
  if (failed) return <ErrorState error={failed} retry={me.error ? me.reload : orgs.reload} />;
  if (!me.data || !orgs.data) return <PageSkeleton />;
  if (me.data.termsPending && !accepted)
    return (
      <Card className="mx-auto mt-16 flex w-full max-w-lg flex-col gap-4 p-6">
        <PageHeader
          centered
          title="Accept the terms to continue"
          description="Your account was created before this Liftgate instance asked for agreement to its terms."
        />
        <Loaded query={providers} skeleton={<Skeleton className="h-4" />}>
          {(list) => (
            <>
              <Consent providers={list} />
              <Button variant="primary" pending={accept.pending} onClick={() => accept.run()} className="w-full">
                Accept
              </Button>
            </>
          )}
        </Loaded>
        <FormError message={accept.error} />
      </Card>
    );
  if (me.data.status === "pending")
    return (
      <Card className="mx-auto mt-16 w-full max-w-lg p-6">
        <PageHeader
          title="Your account is waiting for approval"
          description="The operator of this Liftgate instance approves new accounts by hand. Once yours is approved, this page lets you create your first organization."
        />
      </Card>
    );
  if (orgs.data[0]) redirect(`/${orgs.data[0].slug}${installed ? "?new=project" : ""}`);
  return (
    <Card className="mx-auto mt-16 w-full max-w-lg">
      <CardHeader title="Create your organization" description="Projects and members belong to an organization." />
      <CreateOrgForm className="p-6" />
    </Card>
  );
}
