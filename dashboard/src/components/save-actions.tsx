"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAction, useOnChange } from "@/lib/hooks";
import type { Build, CurrentDeployment, Service } from "@/lib/types";
import { building, shortSha, type Apply } from "@/lib/util";
import { Button } from "./ui/button";
import { FormError } from "./ui/input";

export type Saved = { current: CurrentDeployment | null; apply?: Apply };

export async function saved(serviceId: string) {
  try {
    await api(`/services/${serviceId}/redeploy`, { method: "POST" });
    return "Saved. Redeploying without a rebuild.";
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) return "Saved. Nothing is running yet, so this applies on the first deploy.";
    throw e;
  }
}

export function SaveActions({ serviceId, pending, saved: done, onApplied }: { serviceId: string; pending: boolean; saved?: Saved; onApplied?: () => void }) {
  const [applied, setApplied] = useState<string>();
  useOnChange(() => setApplied(undefined), done);
  const apply = useAction(async () => {
    if (done?.apply === "rebuild") {
      const [{ current }, builds] = await Promise.all([api<Service>(`/services/${serviceId}`), api<Build[]>(`/services/${serviceId}/builds`)]);
      if (builds.some(building)) throw new Error("A build is already in progress. Try again when it finishes.");
      if (!current) return;
      await api(`/services/${serviceId}/deploy`, { method: "POST", body: { ref: current.commitSha } });
      setApplied(`Saved. Rebuilding ${shortSha(current.commitSha)}.`);
    } else setApplied(await saved(serviceId));
    onApplied?.();
  });
  return (
    <div className="flex flex-col items-end gap-2">
      <FormError message={apply.error} />
      <div className="flex flex-wrap items-center justify-end gap-2">
        <span role="status" className="text-sm text-graphite-400">
          {applied ?? (done && (done.current ? "Saved." : "Saved. Applies on the first deploy."))}
        </span>
        {done?.apply && !applied && (
          <Button pending={apply.pending} onClick={() => apply.run()}>
            {done.apply === "rebuild" ? "Rebuild and deploy" : "Redeploy"}
          </Button>
        )}
        <Button type="submit" variant="primary" pending={pending}>
          Save
        </Button>
      </div>
    </div>
  );
}
