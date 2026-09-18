"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import type { Service, ServiceSpec } from "@/lib/types";
import { ServiceForm } from "@/components/service-form";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { FormError } from "@/components/ui/input";

export function SettingsTab({ service, projectHref, onChanged }: { service: Service; projectHref: string; onChanged: () => void }) {
  const router = useRouter();
  const save = useAction(async (spec: ServiceSpec) => {
    await api(`/services/${service.id}`, { method: "PATCH", body: spec });
    onChanged();
  });
  const remove = useAction(async () => {
    if (!window.confirm(`Delete ${service.name}? Its deployments, variables and domains are removed with it.`)) return;
    await api(`/services/${service.id}`, { method: "DELETE" });
    router.push(projectHref);
  });
  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader title="Service settings" description="Changes apply on the next release." />
        <div className="p-6">
          <ServiceForm initial={service} pending={save.pending} error={save.error} submitLabel="Save changes" onSubmit={save.run} />
        </div>
      </Card>
      <Card>
        <CardHeader
          title={<span className="text-danger">Delete service</span>}
          description="Removes the service with its deployments, variables and domains. This cannot be undone."
          actions={
            <Button variant="danger" pending={remove.pending} onClick={() => remove.run()}>
              Delete service
            </Button>
          }
        />
        {remove.error && (
          <div className="px-6 py-4">
            <FormError message={remove.error} />
          </div>
        )}
      </Card>
    </div>
  );
}
