import assert from "node:assert/strict";
import { test } from "node:test";
import { axis, formatMetric, formatTime, nearest, paths, segments } from "./metrics.ts";

const MB = 1024 * 1024;

test("formatMetric shows cpu in millicores and bytes in binary units", () => {
  assert.equal(formatMetric(0.125, "cpu"), "125m");
  assert.equal(formatMetric(1.5, "cpu"), "1,500m");
  assert.equal(formatMetric(0, "bytes"), "0 B");
  assert.equal(formatMetric(212 * MB, "bytes"), "212 MB");
  assert.equal(formatMetric(1536, "bytesPerSecond"), "1.5 KB/s");
});

test("axis rounds the top to a clean step in the unit of the largest value", () => {
  assert.deepEqual(axis(512 * MB, "bytes").ticks.map((t) => t.label), ["0 MB", "200 MB", "400 MB", "600 MB"]);
  assert.equal(axis(512 * MB, "bytes").top, 600 * MB);
  assert.deepEqual(axis(0.12, "cpu").ticks.map((t) => t.label), ["0m", "50m", "100m", "150m"]);
  assert.deepEqual(axis(0, "bytesPerSecond").ticks.map((t) => t.label), ["0 B/s", "0.25 B/s", "0.5 B/s", "0.75 B/s", "1 B/s"]);
});

test("segments break where points are more than a step and a half apart", () => {
  const points = [0, 12, 24, 60, 72].map((time) => ({ time, value: 1 }));
  assert.deepEqual(segments(points, 12).map((run) => run.map((p) => p.time)), [[0, 12, 24], [60, 72]]);
  assert.deepEqual(segments([], 12), []);
});

test("paths map time and value onto the plot and close each area run at the baseline", () => {
  const { line, area } = paths([{ time: 0, value: 0 }, { time: 50, value: 5 }, { time: 100, value: 10 }], { start: 0, end: 100, step: 50, top: 10 });
  assert.equal(line, "M0.0,100.0L500.0,50.0L1000.0,0.0");
  assert.equal(area, "M0.0,100L0.0,100.0L500.0,50.0L1000.0,0.0L1000.0,100Z");
  assert.deepEqual(paths([], { start: 0, end: 100, step: 50, top: 10 }), { line: "", area: "" });
});

test("nearest picks the point closest in time", () => {
  const points = [0, 12, 24].map((time) => ({ time, value: 0 }));
  assert.equal(nearest(points, 17), 1);
  assert.equal(nearest(points, 19), 2);
  assert.equal(nearest(points, -5), 0);
});

test("formatTime adds the date only when the window spans more than one day", () => {
  const at = (iso: string) => Date.parse(iso) / 1000;
  assert.doesNotMatch(formatTime(at("2026-09-29T12:10:00Z"), at("2026-09-29T12:00:00Z"), at("2026-09-29T12:30:00Z")), /Sep/);
  assert.match(formatTime(at("2026-09-29T12:10:00Z"), at("2026-09-22T12:00:00Z"), at("2026-09-29T12:30:00Z")), /Sep/);
});
