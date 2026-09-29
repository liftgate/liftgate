"use client";

import { useCopy } from "@/lib/hooks";
import { Button, Spinner } from "./button";
import { Field, FormError, Input } from "./input";

export function CopyField({ label, value, hint }: { label: string; value: string; hint?: string }) {
  const copy = useCopy();
  return (
    <div className="flex flex-col gap-2">
      <Field label={label} hint={hint}>
        <div className="flex gap-2">
          <Input readOnly value={value} onFocus={(e) => e.currentTarget.select()} className="min-w-0 flex-1 font-mono" />
          <Button onClick={() => copy.run(value)} aria-live="polite">
            {copy.pending && <Spinner />}
            {copy.copied ? "Copied" : "Copy"}
          </Button>
        </div>
      </Field>
      <FormError message={copy.error} />
    </div>
  );
}
