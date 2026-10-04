"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

const itemClasses =
  "flex h-8 w-full shrink-0 items-center gap-2 rounded-md px-2 text-left text-sm text-graphite-200 hover:bg-graphite-800 hover:text-white focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent";

export function Menu({
  label,
  trigger,
  className,
  current,
  popup = "inset-x-0 top-full mt-1",
  top,
  children,
}: {
  label: string;
  trigger: ReactNode;
  className: string;
  current?: boolean;
  popup?: string;
  top?: ReactNode;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    root.current?.querySelector<HTMLElement>("[data-menu] input, [role^=menuitem]")?.focus();
    const outside = (e: PointerEvent) => root.current?.contains(e.target as Node) || setOpen(false);
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [open]);
  const keys = (e: KeyboardEvent) => {
    const items = [...(root.current?.querySelectorAll<HTMLElement>("[role^=menuitem]") ?? [])];
    const at = items.indexOf(document.activeElement as HTMLElement);
    const moves: Record<string, number> = { ArrowDown: (at + 1) % items.length, ArrowUp: at > 0 ? at - 1 : items.length - 1, ...(at >= 0 && { Home: 0, End: items.length - 1 }) };
    if (e.key === "Escape") {
      e.preventDefault();
      setOpen(false);
      button.current?.focus();
    } else if (e.key === "Tab") setOpen(false);
    else if (e.key in moves && items.length) {
      e.preventDefault();
      items[moves[e.key]].focus();
    }
  };
  return (
    <div ref={root} className="relative">
      <button
        ref={button}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        aria-current={current ? "page" : undefined}
        onClick={() => setOpen(!open)}
        onKeyDown={(e) => {
          if (e.key !== "ArrowDown" || open) return;
          e.preventDefault();
          setOpen(true);
        }}
        className={className}
      >
        {trigger}
      </button>
      {open && (
        <div
          data-menu
          onKeyDown={keys}
          onClick={(e) => (e.target as HTMLElement).closest("[role^=menuitem]") && setOpen(false)}
          className={`absolute z-40 flex flex-col gap-1 rounded-lg border border-graphite-700 bg-graphite-900 p-1 shadow-xl transition-opacity duration-150 motion-safe:starting:opacity-0 ${popup}`}
        >
          {top}
          <div id={id} role="menu" aria-label={label} className="flex max-h-80 flex-col overflow-y-auto">
            {children}
          </div>
        </div>
      )}
    </div>
  );
}

export function MenuLink({ href, checked, children }: { href: string; checked?: boolean; children: ReactNode }) {
  return (
    <Link href={href} role={checked === undefined ? "menuitem" : "menuitemradio"} aria-checked={checked} tabIndex={-1} className={itemClasses}>
      {children}
    </Link>
  );
}

export function MenuButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" role="menuitem" tabIndex={-1} onClick={onClick} className={itemClasses}>
      {children}
    </button>
  );
}

export const MenuDivider = () => <div role="separator" className="my-1 border-t border-graphite-700" />;
