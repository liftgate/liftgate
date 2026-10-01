import { stackIcon } from "@/lib/stacks";

const paths = {
  projects: "M2.5 4.5a1 1 0 0 1 1-1h3L8 5h4.5a1 1 0 0 1 1 1v5.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z",
  overview: "M3 3h4v4H3zM9 3h4v4H9zM3 9h4v4H3zM9 9h4v4H9z",
  settings: "M2.5 4.5h5M10.5 4.5h3M9 3v3M2.5 11.5h2M7.5 11.5h6M6 10v3",
  selector: "M5.5 6 8 3.5 10.5 6M5.5 10 8 12.5 10.5 10",
  chevronDown: "M4.5 6.5 8 10l3.5-3.5",
  chevronRight: "M6.5 4.5 10 8l-3.5 3.5",
  back: "M10 4 6 8l4 4",
  check: "M3.5 8.5l3 3 6-7",
  plus: "M8 3.5v9M3.5 8h9",
  external: "M7 3.5H3.5v9h9V9M9.5 3.5h3v3M12.5 3.5l-5 5",
  docs: "M3 3.5h3.5A1.5 1.5 0 0 1 8 5v8a1.5 1.5 0 0 0-1.5-1.5H3zM13 3.5H9.5A1.5 1.5 0 0 0 8 5v8a1.5 1.5 0 0 1 1.5-1.5H13z",
  operator: "M8 2.5 3 4.5V8c0 2.8 2.1 4.8 5 5.5 2.9-.7 5-2.7 5-5.5V4.5z",
  menu: "M2.5 4.5h11M2.5 8h11M2.5 11.5h11",
  more: "M3 8a1 1 0 1 0 2 0 1 1 0 1 0-2 0M7 8a1 1 0 1 0 2 0 1 1 0 1 0-2 0M11 8a1 1 0 1 0 2 0 1 1 0 1 0-2 0",
  account: "M8 7.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 1 0 0 5M3 13.5c.5-2.5 2.5-4 5-4s4.5 1.5 5 4",
  signOut: "M6.5 13.5h-3v-11h3M10.5 5l3 3-3 3M13.5 8h-7",
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, className = "size-4" }: { name: IconName; className?: string }) {
  return (
    <svg viewBox="0 0 16 16" aria-hidden fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" className={`shrink-0 ${className}`}>
      <path d={paths[name]} />
    </svg>
  );
}

export function StackIcon({ id, className = "size-4" }: { id?: string | null; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden className={`shrink-0 fill-current ${className}`}>
      <path d={stackIcon(id)?.path ?? "M4 4h16v16H4zm2 2v12h12V6z"} />
    </svg>
  );
}
