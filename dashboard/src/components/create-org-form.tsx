"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { AuthProviders, Organization } from "@/lib/types";
import { formValues } from "@/lib/util";
import { NameSlugFields } from "./name-slug-fields";
import { Button } from "./ui/button";
import { FormError } from "./ui/input";

export function CreateOrgForm({ prefill, className = "", onCreated, onCancel }: { prefill?: string; className?: string; onCreated?: () => void; onCancel?: () => void }) {
  const router = useRouter();
  const deployDomain = useApi<AuthProviders>("/auth/providers").data?.deployDomain;
  const create = useAction(async (form: HTMLFormElement) => {
    const { name, slug } = formValues(form);
    const org = await api<Organization>("/orgs", { method: "POST", body: { slug, name } });
    onCreated?.();
    router.push(`/new?org=${org.slug}`);
  });
  const named = create.field === "name" || create.field === "slug";
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        create.run(e.currentTarget);
      }}
      className={`flex flex-col gap-4 ${className}`}
    >
      <NameSlugFields
        compact
        prefill={prefill}
        errorAt={(field) => (create.field === field ? create.error : undefined)}
        preview={
          deployDomain
            ? (slug) => (
                <>
                  Addresses end in{" "}
                  <span className="font-mono text-graphite-200">
                    -{slug}.{deployDomain}
                  </span>
                </>
              )
            : undefined
        }
      />
      <FormError message={named ? undefined : create.error} />
      <div className="flex justify-end gap-2">
        {onCancel && <Button onClick={onCancel}>Cancel</Button>}
        <Button type="submit" variant="primary" pending={create.pending}>
          Continue
        </Button>
      </div>
    </form>
  );
}
