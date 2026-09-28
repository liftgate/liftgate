"use client";

import { useCopy } from "@/lib/hooks";
import { Button } from "./button";
import { Field, FormError, Input } from "./input";

export function CopyField({ label, value, hint }: { label: string; value: string; hint?: string }) {
  const copy = useCopy();
  return (
    <div className="flex flex-col gap-2">
      <Field label={label} hint={hint}>
        <div className="flex gap-2">
          <Input readOnly value={value} onFocus={(e) => e.currentTarget.select()} className="flex-1 font-mono" />
          <Button pending={copy.pending} onClick={() => copy.run(value)} aria-live="polite">
            {copy.copied ? "Copied" : "Copy"}
          </Button>
        </div>
      </Field>
      <FormError message={copy.error} />
    </div>
  );
}
