"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { describe } from "@/lib/api";
import { MAX_DOTENV_BYTES, parseDotenv } from "@/lib/dotenv";
import { classifyVariable, keepsStoredValue, mergeDotenv, pasteSummary, type EnvRow } from "@/lib/util";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { FormError, Input, Textarea } from "./ui/input";

const COLLAPSE_AFTER = 20;

export function EnvRows({ rows, onChange, databaseHref }: { rows: EnvRow[]; onChange: (rows: EnvRow[]) => void; databaseHref?: string }) {
  const [all, setAll] = useState(false);
  const shown = all ? rows : rows.slice(0, COLLAPSE_AFTER);
  const update = (i: number, patch: Partial<EnvRow>) => onChange(rows.map((row, j) => (j === i ? { ...row, ...patch, updated: false } : row)));
  return (
    <div className="divide-y divide-graphite-700">
      {shown.map((row, i) => {
        const { buildTime } = classifyVariable(row.name);
        return (
          <div key={i} className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-2 px-6 py-3 max-sm:px-4 md:grid-cols-[minmax(0,5fr)_minmax(0,4fr)_auto_auto] md:py-1">
            <div className="col-span-2 flex min-w-0 items-center gap-2 md:col-span-1">
              <Input
                aria-label="Name"
                required
                pattern="[A-Za-z_][A-Za-z0-9_]*"
                placeholder="NAME"
                value={row.name}
                onChange={(e) => update(i, { name: e.target.value })}
                className="min-w-0 flex-1 font-mono"
              />
              {buildTime && <Badge>Build-time</Badge>}
              {row.required && <Badge>Required</Badge>}
              {row.updated && <Badge tone="accent">Updated from paste</Badge>}
              {databaseHref && row.name === "DATABASE_URL" && !row.value && !keepsStoredValue(row) && (
                <Link href={databaseHref} className="shrink-0 rounded-sm text-xs text-graphite-200 underline underline-offset-2 hover:text-white focus-visible:outline-2 focus-visible:outline-accent">
                  Add a database
                </Link>
              )}
            </div>
            <Input
              aria-label="Value"
              type={row.secret ? "password" : "text"}
              autoComplete="off"
              spellCheck={false}
              placeholder={keepsStoredValue(row) ? "Hidden. Type to replace." : (row.description ?? "value")}
              title={row.description ?? undefined}
              value={row.value ?? ""}
              onChange={(e) => update(i, { value: e.target.value })}
              className="col-span-2 font-mono md:col-span-1"
            />
            <label className="flex items-center gap-2 text-sm text-graphite-200">
              <input type="checkbox" checked={row.secret} disabled={keepsStoredValue(row)} onChange={(e) => update(i, { secret: e.target.checked })} className="accent-accent" />
              Secret
            </label>
            <Button variant="ghost" onClick={() => onChange(rows.filter((_, j) => j !== i))}>
              Remove
            </Button>
            {buildTime && row.secret && (
              <p className="col-span-full text-xs text-warning">Secret variables never become Docker build arguments, so a Dockerfile build won&apos;t see this value.</p>
            )}
          </div>
        );
      })}
      {rows.length > shown.length && (
        <div className="px-6 py-3 max-sm:px-4">
          <Button variant="ghost" onClick={() => setAll(true)}>
            Show {rows.length - shown.length} more
          </Button>
        </div>
      )}
    </div>
  );
}

export function useEnvPaste(rows: EnvRow[], onChange: (rows: EnvRow[]) => void) {
  const [pasting, setPasting] = useState(false);
  const [text, setText] = useState("");
  const [summary, setSummary] = useState<string>();
  const [error, setError] = useState<string>();
  const file = useRef<HTMLInputElement>(null);
  const apply = (input: string) => {
    try {
      const parsed = parseDotenv(input);
      const merged = mergeDotenv(rows, parsed.vars);
      onChange(merged.rows);
      setSummary(pasteSummary(merged.added, merged.updated, parsed.skipped));
      setError(undefined);
      setText("");
      setPasting(false);
    } catch (e) {
      setError(describe(e));
    }
  };
  const open = () => {
    setSummary(undefined);
    setPasting(true);
  };
  const pasteButton = <Button onClick={open}>Paste .env</Button>;
  const importButton = (
    <>
      <Button onClick={() => file.current?.click()}>Import file</Button>
      <input
        ref={file}
        type="file"
        hidden
        aria-label="Import a .env file"
        onChange={(e) => {
          const chosen = e.target.files?.[0];
          e.target.value = "";
          if (!chosen) return;
          if (chosen.size > MAX_DOTENV_BYTES) setError("That file is larger than 256 KB.");
          else chosen.text().then(apply, (failure: unknown) => setError(describe(failure)));
        }}
      />
    </>
  );
  const panel = pasting ? (
    <div className="flex flex-col gap-2 border-b border-graphite-700 px-6 py-4 max-sm:px-4">
      <Textarea
        aria-label="Paste a .env file"
        rows={5}
        autoFocus
        autoComplete="off"
        spellCheck={false}
        placeholder={"API_URL=https://api.example.com\nSESSION_SECRET=..."}
        value={text}
        onChange={(e) => setText(e.target.value)}
        className="font-mono"
      />
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button
          onClick={() => {
            setText("");
            setError(undefined);
            setPasting(false);
          }}
        >
          Cancel
        </Button>
        <Button variant="primary" onClick={() => apply(text)}>
          Add variables
        </Button>
      </div>
    </div>
  ) : (
    (summary || error) && (
      <p role="status" className={`border-b border-graphite-700 px-6 py-3 text-sm max-sm:px-4 ${error ? "text-danger" : "text-graphite-400"}`}>
        {error ?? summary}
      </p>
    )
  );
  return { pasteButton, importButton, panel, open };
}
