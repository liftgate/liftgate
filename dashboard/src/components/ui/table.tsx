import type { ReactNode } from "react";

export function Table({ columns, children }: { columns: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-graphite-700">
      <table className="w-full text-left text-sm">
        <thead className="bg-graphite-900 text-xs uppercase tracking-wide text-graphite-400">
          <tr>
            {columns.map((column, i) => (
              <th key={i} scope="col" className="h-10 px-4 font-medium">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-graphite-700">{children}</tbody>
      </table>
    </div>
  );
}

export function Row({ selected = false, children }: { selected?: boolean; children: ReactNode }) {
  return <tr className={selected ? "bg-graphite-800" : "hover:bg-graphite-800/50"}>{children}</tr>;
}

export function Cell({ mono = false, className = "", children }: { mono?: boolean; className?: string; children?: ReactNode }) {
  return <td className={`h-12 px-4 align-middle ${mono ? "font-mono text-xs" : ""} ${className}`}>{children}</td>;
}
