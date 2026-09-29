"use client";

import { useState, type KeyboardEvent, type PointerEvent } from "react";
import { axis, formatMetric, formatTime, nearest, paths, PLOT_HEIGHT, PLOT_WIDTH, type Measure } from "@/lib/metrics";
import type { MetricPoint } from "@/lib/types";

type Props = {
  label: string;
  points: MetricPoint[];
  start: number;
  end: number;
  step: number;
  measure: Measure;
  limit?: number;
};

const moves: Record<string, (index: number, last: number) => number> = {
  ArrowLeft: (index) => index - 1,
  ArrowRight: (index) => index + 1,
  Home: () => 0,
  End: (_, last) => last,
};

export function LineChart({ label, points, start, end, step, measure, limit }: Props) {
  const [active, setActive] = useState<number>();
  const [readout, setReadout] = useState("");
  const { top, ticks } = axis(Math.max(limit ?? 0, ...points.map((p) => p.value)), measure);
  const { line, area } = paths(points, { start, end, step, top });
  const last = points.length - 1;
  const x = (seconds: number) => (seconds - start) / (end - start);
  const y = (value: number) => 1 - value / top;
  const percent = (fraction: number) => `${fraction * 100}%`;
  const point = active === undefined ? undefined : points[active];
  const marked = point ?? points[last];
  const peak = Math.max(...points.map((p) => p.value));
  const format = (value: number) => formatMetric(value, measure);
  const time = (seconds: number) => formatTime(seconds, start, end);
  const pointer = (e: PointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    setActive(nearest(points, start + ((e.clientX - rect.left) / rect.width) * (end - start)));
  };
  const key = (e: KeyboardEvent<HTMLDivElement>) => {
    const move = moves[e.key];
    if (!move) return;
    e.preventDefault();
    const next = Math.min(last, Math.max(0, move(active ?? last, last)));
    setActive(next);
    setReadout(`${format(points[next].value)}, ${time(points[next].time)}`);
  };
  return (
    <div className="flex flex-col gap-2">
      <div className="flex h-32 gap-2">
        <div aria-hidden className="relative w-16 shrink-0 text-right text-xs tabular-nums text-graphite-400">
          {ticks.map((tick) => (
            <span key={tick.value} className="absolute right-0 -translate-y-1/2 whitespace-nowrap" style={{ top: percent(y(tick.value)) }}>
              {tick.label}
            </span>
          ))}
        </div>
        <div
          role="img"
          aria-label={`${label}: latest ${format(points[last].value)}, peak ${format(peak)}${limit ? `, limit ${format(limit)}` : ""}`}
          tabIndex={0}
          onPointerMove={pointer}
          onPointerLeave={() => setActive(undefined)}
          onFocus={() => setActive(last)}
          onBlur={() => {
            setActive(undefined);
            setReadout("");
          }}
          onKeyDown={key}
          className="relative min-w-0 flex-1 touch-pan-y rounded-sm focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent"
        >
          <svg viewBox={`0 0 ${PLOT_WIDTH} ${PLOT_HEIGHT}`} preserveAspectRatio="none" className="absolute inset-0 size-full overflow-visible">
            {ticks.map((tick) => (
              <line key={tick.value} x1={0} x2={PLOT_WIDTH} y1={y(tick.value) * PLOT_HEIGHT} y2={y(tick.value) * PLOT_HEIGHT} className="stroke-graphite-700" strokeWidth={1} vectorEffect="non-scaling-stroke" />
            ))}
            {limit !== undefined && (
              <line x1={0} x2={PLOT_WIDTH} y1={y(limit) * PLOT_HEIGHT} y2={y(limit) * PLOT_HEIGHT} className="stroke-graphite-400" strokeWidth={1} strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />
            )}
            <path d={area} className="fill-accent/10" />
            <path d={line} fill="none" className="stroke-accent" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
          </svg>
          {limit !== undefined && (
            <span className={`absolute right-0 text-xs text-graphite-400 ${limit / top > 0.75 ? "pt-1" : "-translate-y-full pb-1"}`} style={{ top: percent(y(limit)) }}>
              Limit {format(limit)}
            </span>
          )}
          {point && <div className="pointer-events-none absolute inset-y-0 w-px bg-graphite-600" style={{ left: percent(x(point.time)) }} />}
          <div
            className="pointer-events-none absolute size-2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-accent ring-2 ring-graphite-900"
            style={{ left: percent(x(marked.time)), top: percent(y(marked.value)) }}
          />
          {point && (
            <div
              className="pointer-events-none absolute top-0 z-10 flex flex-col whitespace-nowrap rounded-md border border-graphite-700 bg-graphite-950 px-2 py-1 text-xs"
              style={x(point.time) > 0.5 ? { right: `calc(${percent(1 - x(point.time))} + 8px)` } : { left: `calc(${percent(x(point.time))} + 8px)` }}
            >
              <span className="font-medium tabular-nums text-white">{format(point.value)}</span>
              <span className="text-graphite-400">{time(point.time)}</span>
            </div>
          )}
        </div>
      </div>
      <span className="sr-only" aria-live="polite">
        {readout}
      </span>
      <div aria-hidden className="flex justify-between gap-4 pl-18 text-xs text-graphite-400">
        <span>{time(start)}</span>
        <span>{time(end)}</span>
      </div>
    </div>
  );
}
