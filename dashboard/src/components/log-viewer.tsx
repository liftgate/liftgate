"use client";

import { useEffect, useRef } from "react";
import { useLogSocket, type SocketStatus } from "@/lib/ws";

const labels: Record<SocketStatus, string> = {
  connecting: "Connecting",
  open: "Live",
  closed: "Stream closed",
  error: "Connection failed",
};

export function LogViewer({ path, title }: { path: string; title: string }) {
  const { lines, status } = useLogSocket(path);
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    const element = ref.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [lines]);
  return (
    <div className="overflow-hidden rounded-lg border border-graphite-700 bg-graphite-950">
      <div className="flex h-10 items-center justify-between border-b border-graphite-700 px-4 text-xs">
        <span className="font-medium text-graphite-200">{title}</span>
        <span className={`flex items-center gap-2 ${status === "error" ? "text-danger" : "text-graphite-400"}`}>
          {status === "open" && <span className="size-2 rounded-full bg-accent" />}
          {labels[status]}
        </span>
      </div>
      <pre ref={ref} className="max-h-96 overflow-auto p-4 font-mono text-xs leading-5 text-graphite-200">
        {lines.length ? lines.join("\n") : "Waiting for output…"}
      </pre>
    </div>
  );
}
