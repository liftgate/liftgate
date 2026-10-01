"use client";

import { useState, type ReactNode } from "react";
import { slugify } from "@/lib/util";
import { Field, Input } from "./ui/input";

export const toSlug = (name: string) => slugify(name).slice(0, 40).replace(/-+$/, "");

export function NameSlugFields({
  initial,
  prefill = "",
  prefillSlug,
  label = "Name",
  compact = false,
  editLabel = "Edit",
  preview,
  errorAt,
  onChange,
}: {
  initial?: { name: string; slug: string };
  prefill?: string;
  prefillSlug?: string;
  label?: string;
  compact?: boolean;
  editLabel?: string;
  preview?: (slug: string) => ReactNode;
  errorAt?: (field: string) => string | undefined;
  onChange?: (value: { name: string; slug: string }) => void;
}) {
  const [value, setValue] = useState({ name: initial?.name ?? prefill, slug: initial?.slug ?? prefillSlug ?? toSlug(prefill) });
  const [editing, setEditing] = useState(false);
  const update = (next: { name: string; slug: string }) => {
    setValue(next);
    onChange?.(next);
  };
  const collapsed = compact && !initial && !editing && !errorAt?.("slug");
  const name = (
    <Field label={label} error={errorAt?.("name")}>
      <Input
        name="name"
        required
        pattern=".*\S.*"
        value={value.name}
        onChange={(e) => update({ name: e.target.value, slug: initial || (compact && !collapsed) ? value.slug : toSlug(e.target.value) })}
      />
    </Field>
  );
  if (collapsed)
    return (
      <div className="flex flex-col gap-2">
        {name}
        <input type="hidden" name="slug" value={value.slug} />
        <p className="min-w-0 break-words text-xs text-graphite-400">
          {preview ? preview(value.slug) : <>URL name <span className="font-mono text-graphite-200">{value.slug}</span></>}
          {" · "}
          <button type="button" onClick={() => setEditing(true)} className="rounded-sm text-graphite-200 underline underline-offset-2 hover:text-white focus-visible:outline-2 focus-visible:outline-accent">
            {editLabel}
          </button>
        </p>
      </div>
    );
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        {name}
        <Field label={compact ? "URL name" : "Slug"} hint={initial || compact ? undefined : "2 to 40 lowercase letters, numbers and dashes"} error={errorAt?.("slug")}>
          <Input
            name="slug"
            required
            readOnly={!!initial}
            maxLength={40}
            pattern="(?=.{2,40}$)[a-z0-9]+(-[a-z0-9]+)*"
            autoFocus={editing}
            value={value.slug}
            onChange={(e) => update({ ...value, slug: e.target.value })}
            className={initial ? "font-mono opacity-60" : "font-mono"}
          />
        </Field>
      </div>
      {preview && value.slug && <p className="text-xs text-graphite-400">{preview(value.slug)}</p>}
    </>
  );
}
