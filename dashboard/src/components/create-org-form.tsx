"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import type { Organization } from "@/lib/types";
import { formValues } from "@/lib/util";
import { NameSlugFields } from "./name-slug-fields";
import { Button } from "./ui/button";
import { FormError } from "./ui/input";

export function CreateOrgForm({ className = "", onCreated, onCancel }: { className?: string; onCreated?: () => void; onCancel?: () => void }) {
  const router = useRouter();
  const create = useAction(async (form: HTMLFormElement) => {
    const { name, slug } = formValues(form);
    const org = await api<Organization>("/orgs", { method: "POST", body: { slug, name } });
    onCreated?.();
    router.push(`/${org.slug}`);
  });
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        create.run(e.currentTarget);
      }}
      className={`flex flex-col gap-4 ${className}`}
    >
      <NameSlugFields />
      <FormError message={create.error} />
      <div className="flex justify-end gap-2">
        {onCancel && <Button onClick={onCancel}>Cancel</Button>}
        <Button type="submit" variant="primary" pending={create.pending}>
          Create organization
        </Button>
      </div>
    </form>
  );
}
