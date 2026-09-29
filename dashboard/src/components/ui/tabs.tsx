"use client";

import { useId, useRef, type KeyboardEvent, type ReactNode } from "react";

export function Tabs<T extends string>({
  items,
  value,
  label,
  onChange,
  children,
}: {
  items: readonly { id: T; label: string }[];
  value: T;
  label: string;
  onChange: (id: T) => void;
  children: ReactNode;
}) {
  const id = useId();
  const list = useRef<HTMLDivElement>(null);
  const move = (e: KeyboardEvent, from: number) => {
    const last = items.length - 1;
    const to = ({ ArrowRight: from === last ? 0 : from + 1, ArrowLeft: from === 0 ? last : from - 1, Home: 0, End: last } as Record<string, number>)[e.key];
    if (to === undefined) return;
    e.preventDefault();
    onChange(items[to].id);
    list.current?.querySelectorAll<HTMLElement>("[role=tab]")[to]?.focus();
  };
  return (
    <>
      <div className="border-b border-graphite-700">
        <div ref={list} role="tablist" aria-label={label} className="-mb-px flex gap-4 overflow-x-auto">
          {items.map((item, i) => (
            <button
              key={item.id}
              id={`${id}-${item.id}`}
              type="button"
              role="tab"
              aria-selected={item.id === value}
              aria-controls={`${id}-panel`}
              tabIndex={item.id === value ? 0 : -1}
              onClick={() => onChange(item.id)}
              onKeyDown={(e) => move(e, i)}
              className={`h-10 shrink-0 whitespace-nowrap rounded-t-sm border-b-2 px-1 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent ${
                item.id === value ? "border-accent text-white" : "border-transparent text-graphite-400 hover:text-white"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
      </div>
      <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-${value}`}>
        {children}
      </div>
    </>
  );
}
