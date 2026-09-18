"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { OrgRole, SsoConnection, SsoServiceProvider } from "@/lib/types";
import { formValues } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Field, FormError, Input, Textarea } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { PageSkeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const roles: OrgRole[] = ["member", "admin"];

function CopyField({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  const copy = useAction(async () => {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  });
  return (
    <div className="flex flex-col gap-2">
      <Field label={label}>
        <div className="flex gap-2">
          <Input readOnly value={value} onFocus={(e) => e.currentTarget.select()} className="flex-1 font-mono" />
          <Button pending={copy.pending} onClick={() => copy.run()} aria-live="polite">
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>
      </Field>
      <FormError message={copy.error} />
    </div>
  );
}

export function SsoSettings({ org }: { org: string }) {
  const path = `/orgs/${org}/sso`;
  const sp = useApi<SsoServiceProvider>(`${path}/sp`);
  const connection = useApi<SsoConnection | null>(path, () =>
    api<SsoConnection>(path).catch((e: unknown) => {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }),
  );
  const [saved, setSaved] = useState(false);
  const save = useAction(async (form: HTMLFormElement) => {
    setSaved(false);
    const v = formValues(form);
    await api(path, {
      method: "PUT",
      body: {
        idpEntityId: v.idpEntityId,
        idpSsoUrl: v.idpSsoUrl,
        idpCertificate: v.idpCertificate,
        emailDomains: v.emailDomains.toLowerCase().split(/[\s,]+/).filter(Boolean),
        defaultRole: v.defaultRole,
      },
    });
    setSaved(true);
    connection.reload();
  });
  const remove = useAction(async () => {
    if (!window.confirm("Remove SAML SSO? Members can no longer sign in through your identity provider.")) return;
    await api(path, { method: "DELETE" });
    setSaved(false);
    connection.reload();
  });
  const verify = useAction(async () => {
    await api(`${path}/verify`, { method: "POST" });
    connection.reload();
  });
  if (connection.error?.status === 403)
    return <EmptyState title="Only owners can manage SSO" description="Ask an owner of this organization to configure SAML single sign-on." />;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="SAML single sign-on"
        description="Members with an email in your domains sign in through your identity provider and join this organization."
      />
      <Loaded query={connection} skeleton={<PageSkeleton />}>
        {(current) => (
          <>
            <Card>
              <CardHeader title="Service provider" description="Register Liftgate as an application in your identity provider with these values." />
              <div className="flex flex-col gap-4 p-6">
                <Loaded query={sp} skeleton={<TableSkeleton rows={2} />}>
                  {(urls) => (
                    <>
                      <CopyField label="Entity ID and metadata URL" value={urls.entityId} />
                      <CopyField label="Assertion consumer service URL" value={urls.acsUrl} />
                    </>
                  )}
                </Loaded>
              </div>
            </Card>
            <Card>
              <CardHeader
                title="Identity provider"
                description={current ? "Changes apply to the next SSO sign-in." : "SSO is off until you save a connection."}
              />
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  save.run(e.currentTarget);
                }}
                className="flex flex-col gap-4 p-6"
              >
                <Field label="IdP entity ID">
                  <Input name="idpEntityId" required defaultValue={current?.idpEntityId} className="font-mono" />
                </Field>
                <Field label="SSO URL" hint="The identity provider endpoint for the HTTP-Redirect binding">
                  <Input name="idpSsoUrl" type="url" required defaultValue={current?.idpSsoUrl} placeholder="https://" className="font-mono" />
                </Field>
                <Field label="Signing certificate" hint="PEM encoded X.509 certificate">
                  <Textarea
                    name="idpCertificate"
                    required
                    rows={6}
                    defaultValue={current?.idpCertificate}
                    placeholder="-----BEGIN CERTIFICATE-----"
                    className="font-mono text-xs"
                  />
                </Field>
                <div className="grid grid-cols-2 gap-4">
                  <Field label="Email domains" hint="Separate with commas, for example acme.com, acme.io">
                    <Input name="emailDomains" required defaultValue={current?.emailDomains.join(", ")} className="font-mono" />
                  </Field>
                  <Field label="Default role" hint="Given to people who join through SSO">
                    <Select name="defaultRole" defaultValue={current?.defaultRole ?? "member"}>
                      {roles.map((role) => (
                        <option key={role} value={role}>
                          {role}
                        </option>
                      ))}
                    </Select>
                  </Field>
                </div>
                <FormError message={save.error} />
                {saved && (
                  <p role="status" className="text-sm text-success">
                    SSO connection saved.
                  </p>
                )}
                <div className="flex justify-end">
                  <Button type="submit" variant="primary" pending={save.pending}>
                    {current ? "Save changes" : "Enable SSO"}
                  </Button>
                </div>
              </form>
            </Card>
            {current && (
              <Card>
                <CardHeader
                  title="Email domains"
                  description="Prove each domain with a TXT record. Until a domain is verified, members cannot find this organization at sign-in and SSO never joins an existing account."
                  actions={
                    current.verifiedDomains.length < current.emailDomains.length && (
                      <Button pending={verify.pending} onClick={() => verify.run()}>
                        Verify domains
                      </Button>
                    )
                  }
                />
                <div className="flex flex-col gap-4 p-6">
                  <FormError message={verify.error} />
                  <Table columns={["Domain", "TXT name", "TXT value", "Status"]}>
                    {current.emailDomains.map((domain) => (
                      <Row key={domain}>
                        <Cell mono>{domain}</Cell>
                        <Cell mono className="text-graphite-200">_liftgate.{domain}</Cell>
                        <Cell mono className="text-graphite-200">{current.verificationToken}</Cell>
                        <Cell>
                          <StatusBadge status={current.verifiedDomains.includes(domain) ? "verified" : "pending"} />
                        </Cell>
                      </Row>
                    ))}
                  </Table>
                </div>
              </Card>
            )}
            {current && (
              <Card>
                <CardHeader
                  title={<span className="text-danger">Remove SSO</span>}
                  description="Deletes the connection to your identity provider."
                  actions={
                    <Button variant="danger" pending={remove.pending} onClick={() => remove.run()}>
                      Remove SSO
                    </Button>
                  }
                />
                {remove.error && (
                  <div className="px-6 py-4">
                    <FormError message={remove.error} />
                  </div>
                )}
              </Card>
            )}
          </>
        )}
      </Loaded>
    </div>
  );
}
