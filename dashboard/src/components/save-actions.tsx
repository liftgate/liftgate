"use client";

import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { Button, type ButtonVariant } from "./ui/button";

export const redeployRequested = (e: FormEvent<HTMLFormElement>) => ((e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value === "redeploy";

export async function saved(serviceId: string, redeploy: boolean) {
  if (!redeploy) return "Saved. Applies on the next deploy.";
  try {
    await api(`/services/${serviceId}/redeploy`, { method: "POST" });
    return "Saved. Redeploying without a rebuild.";
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) return "Saved. Nothing is running yet, so this applies on the first deploy.";
    throw e;
  }
}

export function SaveActions({ pending, status }: { pending: boolean; status?: string }) {
  const [clicked, setClicked] = useState<string>();
  const action = (value: string, label: string, variant?: ButtonVariant) => (
    <Button type="submit" value={value} variant={variant} disabled={pending} pending={pending && clicked === value} onClick={() => setClicked(value)}>
      {label}
    </Button>
  );
  return (
    <div className="flex flex-wrap items-center justify-end gap-2">
      <span role="status" className="text-sm text-graphite-400">
        {status}
      </span>
      {action("save", "Save")}
      {action("redeploy", "Save and redeploy", "primary")}
    </div>
  );
}
