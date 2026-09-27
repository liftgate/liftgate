"use client";

import { useState } from "react";
import { useAction } from "@/lib/hooks";
import { Button } from "./button";
import { Field, FormError, Input } from "./input";

export function CopyField({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  const copy = useAction(async () => {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  });
  return (
    <div className="flex flex-col gap-2">
      <Field label={label}>
        <div className="flex gap-2">
          <Input readOnly value={value} onFocus={(e) => e.currentTarget.select()} className="flex-1 font-mono" />
          <Button pending={copy.pending} onClick={() => copy.run()} aria-live="polite">
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>
      </Field>
      <FormError message={copy.error} />
    </div>
  );
}
