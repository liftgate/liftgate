"use client";

import Link from "next/link";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { InvitationPreview, Organization } from "@/lib/types";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { Button, buttonClasses } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { FormError } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";

export function Invitation({ token }: { token: string }) {
  const preview = useApi<InvitationPreview>(`/invitations/${token}`);
  const accept = useAction(async () => {
    const org = await api<Organization>(`/invitations/${token}/accept`, { method: "POST" });
    window.location.replace(`/${org.slug}`);
  });
  const gone = preview.error?.status === 404 || preview.error?.status === 410;
  return (
    <Card className="mx-auto mt-16 flex w-full max-w-lg flex-col gap-6 p-6">
      {gone ? (
        <>
          <PageHeader
            title="This invitation is no longer valid"
            description="It expired, was already used, or the person who sent it can no longer invite you. Ask them for a new link."
          />
          <div className="flex justify-end">
            <Link href="/dashboard" className={buttonClasses()}>
              Go to your organizations
            </Link>
          </div>
        </>
      ) : (
        <Loaded
          query={preview}
          skeleton={
            <div className="flex flex-col gap-2">
              <Skeleton className="h-6 w-48" />
              <Skeleton className="h-4 w-72" />
            </div>
          }
        >
          {(invitation) => (
            <>
              <PageHeader
                title={`Join ${invitation.name}`}
                description={`${invitation.invitedBy} invited you to join ${invitation.slug} as ${invitation.role === "member" ? "a" : "an"} ${invitation.role}.`}
              />
              <FormError message={accept.error} />
              <div className="flex justify-end">
                <Button variant="primary" pending={accept.pending} onClick={() => accept.run()}>
                  Join {invitation.name}
                </Button>
              </div>
            </>
          )}
        </Loaded>
      )}
    </Card>
  );
}
