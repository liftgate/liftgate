"use client";

import { useState, type ReactNode } from "react";
import { Button } from "./ui/button";
import { Card, CardHeader } from "./ui/card";
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
    <Card>
      <CardHeader
        divided={false}
        title={<span className="text-danger">{title}</span>}
        description={description}
        actions={
          <Button variant="danger" disabled={!typed} onClick={() => setOpen(true)}>
            {title}
          </Button>
        }
      />
      <ConfirmDialog open={open} title={title} typed={typed} pending={pending} error={error} onConfirm={onConfirm} onClose={() => setOpen(false)}>
        {children}
      </ConfirmDialog>
    </Card>
  );
}
