"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAction } from "@/lib/hooks";
import type { Service, ServiceSpec } from "@/lib/types";
import { SaveActions, saved } from "@/components/save-actions";
import { ServiceForm } from "@/components/service-form";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { CopyField } from "@/components/ui/copy-field";
import { ConfirmDialog } from "@/components/ui/dialog";

export function SettingsTab({ service, projectHref, onChanged }: { service: Service; projectHref: string; onChanged: () => void }) {
  const router = useRouter();
  const [status, setStatus] = useState<string>();
  const [deleting, setDeleting] = useState(false);
  const save = useAction(async (spec: ServiceSpec, andRedeploy: boolean) => {
    setStatus(undefined);
    await api(`/services/${service.id}`, { method: "PATCH", body: spec });
    setStatus(await saved(service.id, andRedeploy));
    onChanged();
  });
  const remove = useAction(async () => {
    await api(`/services/${service.id}`, { method: "DELETE" });
    router.push(projectHref);
  });
  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader title="Service settings" description="Save keeps changes for the next deploy. Save and redeploy applies them now without a rebuild." />
        <div className="flex flex-col gap-6 p-6">
          {service.internalHost && (
            <CopyField label="Private address" value={service.internalHost} hint="Services in this environment reach it on port 80 and on its own port" />
          )}
          <ServiceForm
            initial={service}
            error={save.error}
            errorField={save.field}
            actions={<SaveActions pending={save.pending} status={status} />}
            onSubmit={(spec, andRedeploy) => save.run(spec, andRedeploy)}
          />
        </div>
      </Card>
      <Card>
        <CardHeader
          title={<span className="text-danger">Delete service</span>}
          description={`Removes the service with its deployments, variables${service.volume ? ", domains and the files on its volume" : " and domains"}. This cannot be undone.`}
          actions={
            <Button variant="danger" onClick={() => setDeleting(true)}>
              Delete service
            </Button>
          }
        />
      </Card>
      <ConfirmDialog
        open={deleting}
        title="Delete service"
        typed={service.slug}
        pending={remove.pending}
        error={remove.error}
        onConfirm={() => remove.run()}
        onClose={() => setDeleting(false)}
      >
        <span className="font-medium text-white">{service.name}</span> is removed with its deployments, variables{service.volume ? ", domains and the files on its volume" : " and domains"}.
      </ConfirmDialog>
    </div>
  );
}
