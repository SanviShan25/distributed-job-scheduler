import {
  CartesianGrid,
  ComposedChart,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from "recharts";
import type { ChartRange, ResponseTimeBucket } from "./history";
import { formatResponseTime } from "./monitors";

/*
 * Loaded lazily by the monitor page, so Recharts is only downloaded there.
 *
 * Under the app's Content-Security-Policy (style-src 'self', no 'unsafe-inline') this is safe:
 * Recharts renders SVG through React, which sets styles through the DOM (allowed), and it never
 * injects <style> elements or style attributes as markup.
 *
 * Colours are the design tokens from index.css (SVG attributes can't use CSS variables everywhere).
 * Against the white card: the line (brand-600) is 6.3:1 and failure markers (down-700) 6.5:1, above the
 * 3:1 WCAG asks of graphics; axis text (ink-muted) is 7.6:1. Failures are triangles, not just red.
 */
const COLORS = {
  line: "#4f46e5", // --color-brand-600
  failure: "#b91c1c", // --color-down-700
  axis: "#475569", // --color-ink-muted
  grid: "#e2e8f0", // --color-line
  cursor: "#64748b", // --color-line-strong
};

interface Point {
  t: number;
  end: number;
  avg: number | null;
  max: number | null;
  checks: number;
  failures: number;
  /** 0 when the bucket had failed checks (drawn as a marker on the bottom axis), otherwise null. */
  marker: number | null;
}

export interface ResponseTimeChartProps {
  range: ChartRange;
  bucketSeconds: number;
  buckets: readonly ResponseTimeBucket[];
  /** Short description for screen readers (the SVG's <desc>). */
  description: string;
}

export default function ResponseTimeChart({
  range,
  bucketSeconds,
  buckets,
  description,
}: ResponseTimeChartProps) {
  const points: Point[] = buckets.map((b) => {
    const t = Date.parse(b.start);
    return {
      t,
      end: t + bucketSeconds * 1000,
      avg: b.avgResponseMs,
      max: b.maxResponseMs,
      checks: b.checks,
      failures: b.failures,
      marker: b.failures > 0 ? 0 : null,
    };
  });
  const first = points[0]?.t ?? 0;
  const last = points[points.length - 1]?.t ?? 0;

  return (
    <div className="h-64 w-full sm:h-72">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart
          data={points}
          margin={{ top: 8, right: 8, bottom: 0, left: 0 }}
          title={`Response time, ${range === "24h" ? "last 24 hours" : "last 7 days"}`}
          desc={description}
        >
          <CartesianGrid stroke={COLORS.grid} vertical={false} />
          <XAxis
            dataKey="t"
            type="number"
            scale="time"
            domain={[first, last]}
            ticks={timeTicks(first, last, range)}
            tickFormatter={(t: number) => formatTick(t, range)}
            tick={{ fill: COLORS.axis, fontSize: 12 }}
            stroke={COLORS.cursor}
            allowDataOverflow
          />
          <YAxis
            tickFormatter={(ms: number) => formatResponseTime(ms)}
            tick={{ fill: COLORS.axis, fontSize: 12 }}
            stroke={COLORS.cursor}
            width={64}
            domain={[0, "auto"]}
            allowDecimals={false}
          />
          <Tooltip
            content={(props: TooltipContentProps) => <BucketTooltip {...props} range={range} />}
            cursor={{ stroke: COLORS.cursor, strokeWidth: 1 }}
            isAnimationActive={false}
          />
          <Line
            dataKey="avg"
            name="Average response time"
            stroke={COLORS.line}
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, fill: COLORS.line, stroke: "#ffffff", strokeWidth: 2 }}
            connectNulls={false}
            isAnimationActive={false}
          />
          {/*
            Failed checks: a triangle on the bottom axis for each bucket that had any. A second line over
            the same data (not a Scatter with its own data, which would put the tooltip on the wrong
            bucket), with no stroke and only these dots.
          */}
          <Line
            dataKey="marker"
            name="Failed checks"
            stroke="none"
            dot={FailureMarker}
            activeDot={false}
            tooltipType="none"
            legendType="none"
            isAnimationActive={false}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

function FailureMarker({ cx, cy, index }: { cx?: number; cy?: number; index?: number }) {
  if (cx == null || cy == null || !Number.isFinite(cy)) return <g key={index} />;
  return (
    <path
      key={index}
      d={`M${String(cx)},${String(cy - 7)} L${String(cx + 6)},${String(cy + 4)} L${String(cx - 6)},${String(cy + 4)} Z`}
      fill={COLORS.failure}
      stroke="#ffffff"
      strokeWidth={1}
    />
  );
}

function BucketTooltip({ active, payload, range }: TooltipContentProps & { range: ChartRange }) {
  const point = (payload[0]?.payload ?? undefined) as Point | undefined;
  if (!active || !point) return null;
  return (
    <div className="rounded-lg border border-line bg-surface px-3 py-2 text-sm text-ink shadow-md">
      <p className="font-semibold">{formatWindow(point, range)}</p>
      {point.checks === 0 ? (
        <p className="text-ink-muted">No checks</p>
      ) : (
        <>
          {point.avg !== null && <p>Average {formatResponseTime(point.avg)}</p>}
          {point.max !== null && point.max !== point.avg && (
            <p className="text-ink-muted">Slowest {formatResponseTime(point.max)}</p>
          )}
          {point.failures > 0 && (
            <p className="font-semibold text-down-700">
              {point.failures === 1 ? "1 failed check" : `${String(point.failures)} failed checks`}
            </p>
          )}
        </>
      )}
    </div>
  );
}

const HOUR_MS = 3_600_000;

/** Every 3 hours on the hour for 24 h; every local midnight for 7 days. */
function timeTicks(first: number, last: number, range: ChartRange): number[] {
  const ticks: number[] = [];
  const start = new Date(first);
  if (range === "24h") {
    start.setMinutes(0, 0, 0);
    start.setHours(Math.ceil(start.getHours() / 3) * 3);
    for (let t = start.getTime(); t <= last; t += 3 * HOUR_MS) if (t >= first) ticks.push(t);
  } else {
    start.setHours(24, 0, 0, 0);
    // Step by calendar day rather than 24 h, so daylight-saving changes stay on midnight.
    for (const d = start; d.getTime() <= last; d.setDate(d.getDate() + 1)) ticks.push(d.getTime());
  }
  return ticks;
}

function formatTick(t: number, range: ChartRange): string {
  return range === "24h"
    ? new Date(t).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })
    : new Date(t).toLocaleDateString(undefined, { weekday: "short", day: "numeric" });
}

function formatWindow(point: Point, range: ChartRange): string {
  const time = (t: number) =>
    new Date(t).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  const day =
    range === "7d"
      ? `${new Date(point.t).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })}, `
      : "";
  return `${day}${time(point.t)}–${time(point.end)}`;
}
