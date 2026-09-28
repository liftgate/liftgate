"use client";

import Link from "next/link";
import { api, ApiError, apiHref } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import { formValues } from "@/lib/util";
import { PageHeader } from "@/components/page-header";
import { Button, buttonClasses } from "@/components/ui/button";
import { Field, FormError, Input } from "@/components/ui/input";

export function SsoSignIn({ next }: { next: string }) {
  const lookup = useAction(async (form: HTMLFormElement) => {
    const { email } = formValues(form);
    const { org } = await api<{ org: string }>(`/auth/sso/lookup?${new URLSearchParams({ email })}`).catch((e: unknown) => {
      throw e instanceof ApiError && e.status === 404 ? new Error("No SAML connection matches that email domain. Try another sign-in method.") : e;
    });
    window.location.assign(apiHref(`/auth/sso/${encodeURIComponent(org)}/login?${new URLSearchParams({ next })}`));
  });
  return (
    <>
      <PageHeader centered title="SAML single sign-on" description="Enter your work email to continue with your organization's identity provider." />
      <form
        onSubmit={(e) => {
          e.preventDefault();
          lookup.run(e.currentTarget);
        }}
        className="flex flex-col gap-4"
      >
        <Field label="Work email">
          <Input name="email" type="email" required autoFocus autoComplete="email" placeholder="you@company.com" />
        </Field>
        <Button type="submit" variant="primary" pending={lookup.pending} className="w-full">
          Continue
        </Button>
        <FormError message={lookup.error} />
      </form>
      <Link href={`/login?${new URLSearchParams({ next })}`} className={buttonClasses("ghost", "w-full")}>
        Other sign-in methods
      </Link>
    </>
  );
}
