"use client";

import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuthProviders, User } from "@/lib/types";
import { Consent } from "./consent";
import { Loaded } from "./loaded";
import { PageHeader } from "./page-header";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { FormError } from "./ui/input";
import { Skeleton } from "./ui/skeleton";

export function TermsGate({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const me = useApi<User>(!pathname.startsWith("/login") && "/me");
  const providers = useApi<AuthProviders>(me.data?.termsPending && "/auth/providers");
  const [accepted, setAccepted] = useState(false);
  const accept = useAction(async () => {
    await api("/me/terms", { method: "POST" });
    setAccepted(true);
  });
  if (!me.data?.termsPending || accepted || pathname === "/account") return children;
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
}
