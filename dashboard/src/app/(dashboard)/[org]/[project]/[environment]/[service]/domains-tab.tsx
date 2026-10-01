"use client";

import { useRef, useState } from "react";
import { api } from "@/lib/api";
import { useAction, useApi, usePolling } from "@/lib/hooks";
import type { AuthProviders, Domain, Service } from "@/lib/types";
import { formValues } from "@/lib/util";
import { DocsLink } from "@/components/docs-link";
import { Loaded } from "@/components/loaded";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { CopyField } from "@/components/ui/copy-field";
import { ConfirmDialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { Skeleton, TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const CHECK_MS = 15_000;
const isCustom = (domain: Domain) => domain.kind.toLowerCase() === "custom";
const unverified = (domain: Domain) => isCustom(domain) && !domain.verifiedAt;
const settling = (domain: Domain) => isCustom(domain) && (!domain.verifiedAt || domain.certificateStatus.toLowerCase() !== "ready");

export function DomainsTab({ service, admin }: { service: Service; admin: boolean }) {
  const domains = useApi<Domain[]>(`/services/${service.id}/domains`);
  const providers = useApi<AuthProviders>(admin && "/auth/providers");
  const [verifying, setVerifying] = useState<string>();
  const [removing, setRemoving] = useState<Domain>();
  const add = useAction(async (form: HTMLFormElement) => {
    const { hostname } = formValues(form);
    await api(`/services/${service.id}/domains`, { method: "POST", body: { hostname } });
    form.reset();
    domains.reload();
  });
  const verify = useAction(async (id: string) => {
    await api(`/domains/${id}/verify`, { method: "POST" });
    domains.reload();
  });
  const remove = useAction(async (domain: Domain) => {
    await api(`/domains/${domain.id}`, { method: "DELETE" });
    setRemoving(undefined);
    domains.reload();
  });
  const checking = useRef(false);
  usePolling(
    !!domains.data?.some(settling),
    () => {
      if (checking.current) return;
      checking.current = true;
      Promise.allSettled(domains.data?.filter((domain) => admin && unverified(domain)).map((domain) => api(`/domains/${domain.id}/verify`, { method: "POST" })) ?? []).then(() => {
        checking.current = false;
        domains.reload();
      });
    },
    CHECK_MS,
  );
  return (
    <div className="flex flex-col gap-6">
      {service.internalHost && (
        <Card className="p-6">
          <CopyField label="Private address" value={service.internalHost} hint="Services in this environment reach it on port 80 and on its own port" />
        </Card>
      )}
      <Card>
        <CardHeader
          title="Domains"
          description="Custom domains are routed once a TXT record proves ownership. Certificates are issued automatically."
        />
        <div className="flex flex-col gap-4 p-6">
          <FormError message={verify.error} />
          <Loaded query={domains} skeleton={<TableSkeleton rows={2} />}>
            {(list) =>
              list.length === 0 ? (
                <EmptyState
                  title="No domains yet"
                  description={
                    <>
                      {admin ? "Add a custom domain below." : "An admin of this organization adds custom domains."} <DocsLink page="custom-domains">How custom domains work</DocsLink>
                    </>
                  }
                />
              ) : (
                <Table columns={["Hostname", "Kind", "Verification", "Certificate", ""]}>
                  {list.map((domain) => (
                    <Row key={domain.id}>
                      <Cell mono>
                        <a href={`https://${domain.hostname}`} target="_blank" rel="noreferrer" className="hover:text-accent">
                          {domain.hostname}
                        </a>
                        {domain.verifiedAt && domain.certificateMessage && (
                          <p className="mt-1 max-w-md font-sans text-xs text-graphite-400">{domain.certificateMessage}</p>
                        )}
                      </Cell>
                      <Cell>
                        <StatusBadge status={domain.kind} />
                      </Cell>
                      <Cell>
                        <StatusBadge status={domain.verifiedAt ? "verified" : "pending"} />
                      </Cell>
                      <Cell>
                        {domain.verifiedAt ? (
                          <StatusBadge status={domain.certificateStatus} />
                        ) : (
                          <span className="text-xs text-graphite-400">After verification</span>
                        )}
                      </Cell>
                      <Cell className="text-right">
                        {admin && isCustom(domain) && (
                          <div className="flex justify-end gap-2">
                            {!domain.verifiedAt && (
                              <Button
                                pending={verify.pending && verifying === domain.id}
                                onClick={() => {
                                  setVerifying(domain.id);
                                  verify.run(domain.id);
                                }}
                              >
                                Verify
                              </Button>
                            )}
                            <Button variant="danger" onClick={() => setRemoving(domain)}>
                              Remove
                            </Button>
                          </div>
                        )}
                      </Cell>
                    </Row>
                  ))}
                </Table>
              )
            }
          </Loaded>
          {admin && domains.data?.filter((domain) => settling(domain) && domain.dnsRecords.length > 0).map((domain) => (
            <div key={domain.id} className="flex flex-col gap-4 rounded-lg border border-graphite-700 p-4">
              <div className="flex flex-col gap-1">
                <h3 className="text-sm font-medium">
                  DNS records for <span className="font-mono">{domain.hostname}</span>
                </h3>
                <p className="text-xs text-graphite-400">
                  {domain.verifiedAt
                    ? "Keep the CNAME record in place while the certificate is issued."
                    : "Create these records at your DNS provider. Liftgate checks them every 15 seconds."}
                </p>
              </div>
              {domain.dnsRecords.map((record) => (
                <div key={`${record.type} ${record.name}`} className="grid gap-4 md:grid-cols-2">
                  <CopyField label={`${record.type} name`} value={record.name} />
                  <CopyField label={record.type === "CNAME" ? "CNAME target" : `${record.type} value`} value={record.value} />
                </div>
              ))}
            </div>
          ))}
        </div>
      </Card>
      {admin && (
        <Loaded query={providers} skeleton={<Skeleton className="h-40" />}>
          {({ customDomains }) =>
            customDomains ? (
              <Card>
                <CardHeader
                  title="Add a custom domain"
                  description="Liftgate then lists the DNS records to create. An apex domain needs CNAME flattening or an ALIAS record at your DNS provider."
                />
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    add.run(e.currentTarget);
                  }}
                  className="flex flex-col gap-4 p-6"
                >
                  <div className="flex gap-2">
                    <Input name="hostname" required placeholder="app.example.com" pattern="[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}" className="min-w-0 max-w-sm flex-1 font-mono" />
                    <Button type="submit" variant="primary" pending={add.pending}>
                      Add domain
                    </Button>
                  </div>
                  <FormError message={add.error} />
                </form>
              </Card>
            ) : (
              <EmptyState
                title="Custom domains are not enabled"
                description={
                  <>
                    This installation serves web and static services on their platform hostname only. You can add your own domain here once custom domains are enabled.{" "}
                    <DocsLink page="custom-domains">How custom domains work</DocsLink>
                  </>
                }
              />
            )
          }
        </Loaded>
      )}
      <ConfirmDialog
        open={!!removing}
        title="Remove domain"
        pending={remove.pending}
        error={remove.error}
        onConfirm={() => removing && remove.run(removing)}
        onClose={() => setRemoving(undefined)}
      >
        <span className="font-mono text-white">{removing?.hostname}</span> stops routing to this service.
      </ConfirmDialog>
    </div>
  );
}
