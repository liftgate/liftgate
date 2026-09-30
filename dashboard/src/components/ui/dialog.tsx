"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button, type ButtonVariant } from "./button";
import { Field, FormError, Input } from "./input";

export function Dialog({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    if (open && !element.open) element.showModal();
    if (!open && element.open) element.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onClose={onClose}
      className="inset-x-4 m-auto w-auto max-w-lg rounded-lg border border-graphite-700 bg-graphite-900 p-0 text-white shadow-2xl backdrop:bg-black/60"
    >
      {open && (
        <div className="flex flex-col gap-6 p-6">
          <h2 className="text-base font-semibold">{title}</h2>
          {children}
        </div>
      )}
    </dialog>
  );
}

export function ConfirmDialog({
  open,
  title,
  typed,
  prompt,
  variant = "danger",
  pending,
  error,
  onConfirm,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  typed?: string;
  prompt?: string;
  variant?: ButtonVariant;
  pending: boolean;
  error?: string;
  onConfirm: (value: string) => void;
  onClose: () => void;
  children: ReactNode;
}) {
  const [value, setValue] = useState("");
  const close = () => {
    setValue("");
    onClose();
  };
  return (
    <Dialog open={open} title={title} onClose={close}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          onConfirm(value.trim());
        }}
        className="flex flex-col gap-4"
      >
        <p className="text-sm text-graphite-200">{children}</p>
        {(typed || prompt) && (
          <Field label={prompt ?? `Type ${typed} to confirm`}>
            <Input value={value} onChange={(e) => setValue(e.target.value)} required autoComplete="off" autoFocus className={typed ? "font-mono" : undefined} />
          </Field>
        )}
        <FormError message={error} />
        <div className="flex justify-end gap-2">
          <Button onClick={close}>Cancel</Button>
          <Button type="submit" variant={variant} pending={pending} disabled={!!typed && value !== typed}>
            {title}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
