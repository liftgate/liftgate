"use client";

import { useState } from "react";
import { slugify } from "@/lib/util";
import { Field, Input } from "./ui/input";

export function NameSlugFields({ initial }: { initial?: { name: string; slug: string } }) {
  const [value, setValue] = useState({ name: initial?.name ?? "", slug: initial?.slug ?? "" });
  return (
    <div className="grid grid-cols-2 gap-4">
      <Field label="Name">
        <Input
          name="name"
          required
          value={value.name}
          onChange={(e) => setValue({ name: e.target.value, slug: initial ? value.slug : slugify(e.target.value) })}
        />
      </Field>
      <Field label="Slug" hint={initial ? "Slugs cannot be changed" : "Lowercase letters, numbers and dashes"}>
        <Input
          name="slug"
          required
          readOnly={!!initial}
          pattern="[a-z0-9]+(-[a-z0-9]+)*"
          value={value.slug}
          onChange={(e) => setValue({ ...value, slug: e.target.value })}
          className={initial ? "font-mono opacity-60" : "font-mono"}
        />
      </Field>
    </div>
  );
}
