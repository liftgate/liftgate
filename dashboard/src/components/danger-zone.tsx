"use client";

import { useState, type ReactNode } from "react";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { ConfirmDialog } from "./ui/dialog";

export function DangerZone({
  title,
  description,
  typed,
  pending,
  error,
  onConfirm,
  children,
}: {
  title: string;
  description: string;
  typed?: string;
  pending: boolean;
  error?: string;
  onConfirm: () => void;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Card className="flex flex-col gap-4 p-6 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <h2 className="text-base font-medium text-danger">{title}</h2>
        <p className="mt-1 text-sm text-graphite-400">{description}</p>
      </div>
      <Button variant="danger" disabled={!typed} onClick={() => setOpen(true)} className="self-start sm:self-center">
        {title}
      </Button>
      <ConfirmDialog open={open} title={title} typed={typed} pending={pending} error={error} onConfirm={onConfirm} onClose={() => setOpen(false)}>
        {children}
      </ConfirmDialog>
    </Card>
  );
}
