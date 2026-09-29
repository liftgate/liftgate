"use client";

import { useState, type ReactNode } from "react";
import { slugify } from "@/lib/util";
import { Field, Input } from "./ui/input";

const toSlug = (name: string) => slugify(name).slice(0, 40).replace(/-+$/, "");

export function NameSlugFields({
  initial,
  prefill = "",
  preview,
  errorAt,
}: {
  initial?: { name: string; slug: string };
  prefill?: string;
  preview?: (slug: string) => ReactNode;
  errorAt?: (field: string) => string | undefined;
}) {
  const [value, setValue] = useState({ name: initial?.name ?? prefill, slug: initial?.slug ?? toSlug(prefill) });
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" error={errorAt?.("name")}>
          <Input
            name="name"
            required
            pattern=".*\S.*"
            value={value.name}
            onChange={(e) => setValue({ name: e.target.value, slug: initial ? value.slug : toSlug(e.target.value) })}
          />
        </Field>
        <Field label="Slug" hint={initial ? "Slugs cannot be changed" : "2 to 40 lowercase letters, numbers and dashes"} error={errorAt?.("slug")}>
          <Input
            name="slug"
            required
            readOnly={!!initial}
            maxLength={40}
            pattern="(?=.{2,40}$)[a-z0-9]+(-[a-z0-9]+)*"
            value={value.slug}
            onChange={(e) => setValue({ ...value, slug: e.target.value })}
            className={initial ? "font-mono opacity-60" : "font-mono"}
          />
        </Field>
      </div>
      {preview && value.slug && preview(value.slug)}
    </>
  );
}
