import type { ReactNode } from "react";

const tones = {
  neutral: "border-graphite-600 text-graphite-200",
  accent: "border-accent/40 text-accent",
  success: "border-success/40 text-success",
  warning: "border-warning/40 text-warning",
  danger: "border-danger/40 text-danger",
};

export type Tone = keyof typeof tones;

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span className={`inline-flex h-6 items-center rounded-md border px-2 text-xs font-medium ${tones[tone]}`}>{children}</span>
  );
}

const statusTones: Record<string, Tone> = {
  succeeded: "success",
  running: "success",
  verified: "success",
  ready: "success",
  failed: "danger",
  cancelled: "danger",
  queued: "warning",
  pending: "warning",
  releasing: "warning",
  superseded: "neutral",
  rolled_back: "neutral",
};

export function StatusBadge({ status }: { status: string }) {
  const key = status.toLowerCase();
  return <Badge tone={statusTones[key] ?? "neutral"}>{key.replace("_", " ")}</Badge>;
}
