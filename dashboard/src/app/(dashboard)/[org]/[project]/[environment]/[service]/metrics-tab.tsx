"use client";

import { useState } from "react";
import { useApi, usePolling } from "@/lib/hooks";
import { formatMetric, type Measure } from "@/lib/metrics";
import type { Service, ServiceMetrics } from "@/lib/types";
import { LineChart } from "@/components/line-chart";
import { Loaded } from "@/components/loaded";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";

const ranges = { "1h": "hour", "6h": "6 hours", "24h": "24 hours", "7d": "7 days" } as const;

type Range = keyof typeof ranges;

const charts: { key: "cpu" | "memory" | "networkRx" | "networkTx"; title: string; description: string; measure: Measure }[] = [
  { key: "cpu", title: "CPU", description: "Millicores used by all instances", measure: "cpu" },
  { key: "memory", title: "Memory", description: "Working set of the largest instance", measure: "bytes" },
  { key: "networkRx", title: "Network in", description: "Received by all instances", measure: "bytesPerSecond" },
  { key: "networkTx", title: "Network out", description: "Sent by all instances", measure: "bytesPerSecond" },
];

export function MetricsTab({ service }: { service: Service }) {
  const [range, setRange] = useState<Range>("1h");
  const query = useApi<ServiceMetrics>(`/services/${service.id}/metrics?range=${range}`);
  const [shown, setShown] = useState<ServiceMetrics>();
  if (query.data && query.data !== shown) setShown(query.data);
  usePolling(!query.error, query.reload, 30_000);
  const data = query.data ?? shown;
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div role="group" aria-label="Time range" className="flex gap-1">
          {(Object.keys(ranges) as Range[]).map((r) => (
            <Button key={r} variant={r === range ? "secondary" : "ghost"} aria-pressed={r === range} onClick={() => setRange(r)}>
              {r}
            </Button>
          ))}
        </div>
        {data ? (
          <p className="text-sm text-graphite-400">
            <span className="font-medium text-white">{data.restarts}</span> {data.restarts === 1 ? "restart" : "restarts"} across current instances
          </p>
        ) : (
          !query.error && <Skeleton className="h-5 w-48" />
        )}
      </div>
      <Loaded query={{ ...query, data }} skeleton={<Charts />}>
        {(metrics) =>
          charts.every((c) => metrics[c.key].length === 0) ? (
            <EmptyState title={`No metrics in the last ${ranges[range]}`} description="Charts appear a few minutes after a deployment of this service starts running." />
          ) : (
            <Charts metrics={metrics} stale={!query.data} />
          )
        }
      </Loaded>
    </div>
  );
}

function Charts({ metrics, stale }: { metrics?: ServiceMetrics; stale?: boolean }) {
  return (
    <div className={`grid gap-4 transition-opacity md:grid-cols-2 ${stale ? "opacity-60" : ""}`}>
      {charts.map((chart) => {
        const points = metrics?.[chart.key] ?? [];
        const latest = points.at(-1);
        return (
          <Card key={chart.key} className="flex flex-col gap-4 p-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <h2 className="text-sm font-medium">{chart.title}</h2>
                <p className="mt-1 text-xs text-graphite-400">{chart.description}</p>
              </div>
              {metrics ? (
                <p className={`text-sm font-medium ${latest ? "text-white" : "text-graphite-400"}`}>{latest ? formatMetric(latest.value, chart.measure) : "No data"}</p>
              ) : (
                <Skeleton className="h-5 w-16" />
              )}
            </div>
            {!metrics ? (
              <Skeleton className="h-38" />
            ) : latest ? (
              <LineChart
                label={chart.title}
                points={points}
                start={metrics.start}
                end={metrics.end}
                step={metrics.step}
                measure={chart.measure}
                limit={chart.key === "memory" ? metrics.memoryLimitBytes : undefined}
              />
            ) : (
              <p className="flex h-38 items-center justify-center text-sm text-graphite-400">No data in this period</p>
            )}
          </Card>
        );
      })}
    </div>
  );
}
