"use client";

import { redirect, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { Organization } from "@/lib/types";
import { formValues } from "@/lib/util";
import { NameSlugFields } from "@/components/name-slug-fields";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/empty-state";
import { FormError } from "@/components/ui/input";
import { PageSkeleton } from "@/components/ui/skeleton";

export default function Home() {
  const router = useRouter();
  const orgs = useApi<Organization[]>("/orgs");
  const create = useAction(async (form: HTMLFormElement) => {
    const { name, slug } = formValues(form);
    const org = await api<Organization>("/orgs", { method: "POST", body: { slug, name } });
    router.push(`/${org.slug}`);
  });
  if (orgs.error) return <ErrorState error={orgs.error} retry={orgs.reload} />;
  if (!orgs.data) return <PageSkeleton />;
  if (orgs.data[0]) redirect(`/${orgs.data[0].slug}`);
  return (
    <Card className="mx-auto mt-16 w-full max-w-lg">
      <CardHeader title="Create your organization" description="Projects and members belong to an organization." />
      <form
        onSubmit={(e) => {
          e.preventDefault();
          create.run(e.currentTarget);
        }}
        className="flex flex-col gap-4 p-6"
      >
        <NameSlugFields />
        <FormError message={create.error} />
        <div className="flex justify-end">
          <Button type="submit" variant="primary" pending={create.pending}>
            Create organization
          </Button>
        </div>
      </form>
    </Card>
  );
}
