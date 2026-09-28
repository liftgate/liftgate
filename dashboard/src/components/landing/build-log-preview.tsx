"use client";

import { useEffect, useRef, useState } from "react";
import { LogPanel } from "@/components/log-viewer";

export function BuildLogPreview({ title, lines }: { title: string; lines: string[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const [count, setCount] = useState(lines.length);

  useEffect(() => {
    const element = ref.current;
    if (!element || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    let interval: ReturnType<typeof setInterval> | undefined;
    let first = true;
    const observer = new IntersectionObserver(
      ([entry]) => {
        const visible = entry.intersectionRatio >= 0.4;
        if (first) {
          first = false;
          if (visible) observer.disconnect();
          else setCount(0);
          return;
        }
        if (!visible) return;
        observer.disconnect();
        timeout = setTimeout(() => {
          let shown = 0;
          interval = setInterval(() => {
            shown += 1;
            setCount(shown);
            if (shown >= lines.length) clearInterval(interval);
          }, 80);
        }, 400);
      },
      { threshold: 0.4 },
    );
    observer.observe(element);
    return () => {
      observer.disconnect();
      clearTimeout(timeout);
      clearInterval(interval);
    };
  }, [lines.length]);

  return (
    <div ref={ref}>
      <LogPanel title={title} lines={lines.slice(0, count)} status="open" className="h-128 max-md:overflow-hidden max-md:mask-r-from-[calc(100%-4rem)]" />
    </div>
  );
}
