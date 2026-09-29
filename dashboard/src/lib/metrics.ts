import type { MetricPoint } from "./types";

export type Measure = "cpu" | "bytes" | "bytesPerSecond";

export const PLOT_WIDTH = 1000;
export const PLOT_HEIGHT = 100;

const number = new Intl.NumberFormat("en-US", { maximumSignificantDigits: 3 });
const byteUnits = ["B", "KB", "MB", "GB", "TB"];

const unitOf = (value: number, measure: Measure) => {
  if (measure === "cpu") return { divisor: 0.001, suffix: "m" };
  const power = Math.min(byteUnits.length - 1, Math.max(0, Math.floor(Math.log(value) / Math.log(1024))));
  return { divisor: 1024 ** power, suffix: ` ${byteUnits[power]}${measure === "bytesPerSecond" ? "/s" : ""}` };
};

export const formatMetric = (value: number, measure: Measure, unit = unitOf(value, measure)) => `${number.format(value / unit.divisor)}${unit.suffix}`;

const day = (seconds: number) => new Date(seconds * 1000).toDateString();

export const formatTime = (seconds: number, start: number, end: number) =>
  new Date(seconds * 1000).toLocaleString(undefined, day(start) === day(end) ? { hour: "2-digit", minute: "2-digit" } : { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });

export function axis(max: number, measure: Measure) {
  const unit = unitOf(max, measure);
  const scaled = max / unit.divisor || 1;
  const magnitude = 10 ** Math.floor(Math.log10(scaled / 4));
  const step = [1, 2, 2.5, 5].map((n) => n * magnitude).find((s) => scaled / s <= 4) ?? 10 * magnitude;
  const ticks = Array.from({ length: Math.ceil(scaled / step) + 1 }, (_, i) => i * step * unit.divisor);
  return { top: ticks[ticks.length - 1], ticks: ticks.map((value) => ({ value, label: formatMetric(value, measure, unit) })) };
}

export const segments = (points: MetricPoint[], step: number) =>
  points.reduce<MetricPoint[][]>((all, point, i) => {
    if (i > 0 && point.time - points[i - 1].time <= step * 1.5) all[all.length - 1].push(point);
    else all.push([point]);
    return all;
  }, []);

export function paths(points: MetricPoint[], { start, end, step, top }: { start: number; end: number; step: number; top: number }) {
  const x = (time: number) => (((time - start) / (end - start)) * PLOT_WIDTH).toFixed(1);
  const y = (value: number) => (PLOT_HEIGHT - (value / top) * PLOT_HEIGHT).toFixed(1);
  const runs = segments(points, step);
  const coords = (run: MetricPoint[]) => run.map((p) => `${x(p.time)},${y(p.value)}`).join("L");
  return {
    line: runs.map((run) => `M${coords(run)}`).join(""),
    area: runs.map((run) => `M${x(run[0].time)},${PLOT_HEIGHT}L${coords(run)}L${x(run[run.length - 1].time)},${PLOT_HEIGHT}Z`).join(""),
  };
}

export const nearest = (points: MetricPoint[], time: number) =>
  points.reduce((best, point, i) => (Math.abs(point.time - time) < Math.abs(points[best].time - time) ? i : best), 0);
