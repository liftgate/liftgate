"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import type { Service, ServiceSpec } from "@/lib/types";
import { redeploy, SaveActions } from "@/components/save-actions";
import { ServiceForm } from "@/components/service-form";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { FormError } from "@/components/ui/input";

export function SettingsTab({ service, projectHref, onChanged }: { service: Service; projectHref: string; onChanged: () => void }) {
  const router = useRouter();
  const [status, setStatus] = useState<string>();
  const save = useAction(async (spec: ServiceSpec, andRedeploy: boolean) => {
    await api(`/services/${service.id}`, { method: "PATCH", body: spec });
    setStatus(andRedeploy ? await redeploy(service.id) : "Saved. Applies on the next deploy.");
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
        <CardHeader title="Service settings" description="Save keeps changes for the next deploy. Save and redeploy applies them now without a rebuild." />
        <div className="p-6">
          <ServiceForm
            initial={service}
            pending={save.pending}
            error={save.error}
            actions={<SaveActions pending={save.pending} status={status} />}
            onSubmit={(spec, _, andRedeploy) => save.run(spec, andRedeploy)}
          />
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
