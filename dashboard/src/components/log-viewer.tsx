"use client";

import { useEffect, useRef } from "react";
import { useCopy } from "@/lib/hooks";
import { slugify } from "@/lib/util";
import { useLogSocket, type SocketStatus } from "@/lib/ws";
import { Button, Spinner } from "./ui/button";

const FOLLOW_SLACK_PX = 32;

const labels: Record<SocketStatus, string> = {
  connecting: "Connecting",
  open: "Live",
  closed: "Stream closed",
  error: "Connection failed",
};

export function LogViewer({ path, title, detail }: { path: string; title: string; detail?: string }) {
  const { lines, status } = useLogSocket(path);
  return <LogPanel title={title} detail={detail} lines={lines} status={status} />;
}

export function LogPanel({ title, detail, lines, status, className = "h-96" }: { title: string; detail?: string; lines: string[]; status: SocketStatus; className?: string }) {
  const copy = useCopy();
  const ref = useRef<HTMLPreElement>(null);
  const following = useRef(true);
  const text = lines.join("\n");
  useEffect(() => {
    const element = ref.current;
    if (element && following.current) element.scrollTop = element.scrollHeight;
  }, [lines]);
  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    Object.assign(document.createElement("a"), { href: url, download: `${slugify(title)}.log` }).click();
    setTimeout(() => URL.revokeObjectURL(url));
  };
  return (
    <div className="overflow-hidden rounded-lg border border-graphite-700 bg-graphite-950">
      <div className="flex min-h-12 flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-graphite-700 px-4 py-2 text-xs">
        <span className="font-medium text-graphite-200">
          {title}
          {detail && <span className="font-normal text-graphite-400"> · {detail}</span>}
        </span>
        <div className="flex items-center gap-2">
          <span className={`mr-2 flex items-center gap-2 ${status === "error" ? "text-danger" : "text-graphite-400"}`}>
            {status === "connecting" && <Spinner />}
            {status === "open" && <span className="size-2 rounded-full bg-accent" />}
            {labels[status]}
          </span>
          <Button variant="ghost" disabled={!lines.length} onClick={() => copy.run(text)} aria-live="polite">
            {copy.pending && <Spinner />}
            {copy.copied ? "Copied" : "Copy"}
          </Button>
          <Button variant="ghost" disabled={!lines.length} onClick={download}>
            Download
          </Button>
        </div>
      </div>
      <pre
        ref={ref}
        role="region"
        aria-label={`${title} output`}
        tabIndex={0}
        onScroll={(e) => {
          const element = e.currentTarget;
          following.current = element.scrollHeight - element.scrollTop - element.clientHeight <= FOLLOW_SLACK_PX;
        }}
        className={`${className} overflow-auto p-4 font-mono text-xs leading-5 text-graphite-200 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent`}
      >
        {lines.length ? text : status === "closed" ? "No output was recorded." : "Waiting for output…"}
      </pre>
    </div>
  );
}
