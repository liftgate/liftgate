"use client";

import { useEffect, useRef, type ReactNode } from "react";

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
      className="m-auto w-full max-w-lg rounded-lg border border-graphite-700 bg-graphite-900 p-0 text-white shadow-2xl backdrop:bg-black/60"
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
