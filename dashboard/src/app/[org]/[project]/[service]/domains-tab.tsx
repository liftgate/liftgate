"use client";

import { api } from "@/lib/api";
import { useAction, useApi } from "@/lib/hooks";
import type { Domain, Service } from "@/lib/types";
import { formValues } from "@/lib/util";
import { Loaded } from "@/components/loaded";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { FormError, Input } from "@/components/ui/input";
import { TableSkeleton } from "@/components/ui/skeleton";
import { Cell, Row, Table } from "@/components/ui/table";

const isCustom = (domain: Domain) => domain.kind.toLowerCase() === "custom";

export function DomainsTab({ service }: { service: Service }) {
  const domains = useApi<Domain[]>(`/services/${service.id}/domains`);
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
    if (!window.confirm(`Remove ${domain.hostname}?`)) return;
    await api(`/domains/${domain.id}`, { method: "DELETE" });
    domains.reload();
  });
  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader
          title="Domains"
          description="Custom domains are routed once a TXT record proves ownership. Certificates are issued automatically."
        />
        <div className="flex flex-col gap-4 p-6">
          <FormError message={verify.error ?? remove.error} />
          <Loaded query={domains} skeleton={<TableSkeleton rows={2} />}>
            {(list) =>
              list.length === 0 ? (
                <EmptyState title="No domains yet" description="Add a custom domain below." />
              ) : (
                <Table columns={["Hostname", "Kind", "Verification", "Certificate", ""]}>
                  {list.map((domain) => (
                    <Row key={domain.id}>
                      <Cell mono>
                        <a href={`https://${domain.hostname}`} target="_blank" rel="noreferrer" className="hover:text-accent">
                          {domain.hostname}
                        </a>
                        {isCustom(domain) && !domain.verifiedAt && domain.verificationToken && (
                          <p className="mt-1 max-w-md font-sans text-xs text-graphite-400">
                            Create a TXT record at <code className="font-mono text-graphite-200">_liftgate.{domain.hostname}</code> with the value{" "}
                            <code className="font-mono text-graphite-200">{domain.verificationToken}</code>, then verify.
                          </p>
                        )}
                      </Cell>
                      <Cell>
                        <StatusBadge status={domain.kind} />
                      </Cell>
                      <Cell>
                        <StatusBadge status={domain.verifiedAt ? "verified" : "pending"} />
                      </Cell>
                      <Cell>
                        <StatusBadge status={domain.certificateStatus} />
                      </Cell>
                      <Cell className="text-right">
                        {isCustom(domain) && (
                          <div className="flex justify-end gap-2">
                            {!domain.verifiedAt && (
                              <Button pending={verify.pending} onClick={() => verify.run(domain.id)}>
                                Verify
                              </Button>
                            )}
                            <Button variant="danger" pending={remove.pending} onClick={() => remove.run(domain)}>
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
        </div>
      </Card>
      <Card>
        <CardHeader title="Add a custom domain" description="Ownership is checked with a TXT record; point the hostname at your Liftgate gateway to receive traffic." />
        <form
          onSubmit={(e) => {
            e.preventDefault();
            add.run(e.currentTarget);
          }}
          className="flex flex-col gap-4 p-6"
        >
          <div className="flex gap-2">
            <Input name="hostname" required placeholder="app.example.com" pattern="[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}" className="max-w-sm flex-1 font-mono" />
            <Button type="submit" variant="primary" pending={add.pending}>
              Add domain
            </Button>
          </div>
          <FormError message={add.error} />
        </form>
      </Card>
    </div>
  );
}
