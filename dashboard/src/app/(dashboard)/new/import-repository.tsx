"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useApi } from "@/lib/hooks";
import type { Organization, Usage } from "@/lib/types";
import { planName } from "@/lib/util";
import { PageHeader } from "@/components/page-header";
import { RepoPicker } from "@/components/repo-picker";
import { Field } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

export function ImportRepository({ org }: { org: string }) {
  const router = useRouter();
  const orgs = useApi<Organization[]>("/orgs").data;
  const usage = useApi<Usage>(`/orgs/${org}/usage`).data;
  const limit = usage?.limits.projects ?? null;
  const full = !!usage && limit !== null && usage.projects >= limit;
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
      <PageHeader title="Import a repository" description="Pick the GitHub repository to deploy. Liftgate reads it and fills in the rest." />
      {orgs && orgs.length > 1 && (
        <Field label="Organization">
          <Select value={org} onChange={(e) => router.replace(`/new?org=${e.target.value}`)} className="sm:max-w-xs">
            {orgs.map((o) => (
              <option key={o.slug} value={o.slug}>
                {o.name}
              </option>
            ))}
          </Select>
        </Field>
      )}
      {full && usage && (
        <p role="status" className="text-sm text-warning">
          <Link href={`/${org}/settings`} className="underline underline-offset-2 hover:text-white">
            Your {planName(usage.plan)} plan allows {limit} project{limit === 1 ? "" : "s"}
          </Link>
          . Delete one to import another.
        </p>
      )}
      <RepoPicker key={org} org={org} disabled={full} />
    </div>
  );
}
