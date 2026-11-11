/**
 * Maintenance prediction engine, server copy.
 *
 * Mirrors `app/src/main/java/com/kronokeep/app/domain/engine/MaintenanceEngine.kt` line for line; both must
 * pass every case in `engine-fixtures/`. Pure and deterministic: `now` is always a parameter.
 * All arithmetic is epoch milliseconds as doubles, rounded to whole seconds, like the Kotlin engine.
 *
 * Erasable TypeScript only (no enums, namespaces or parameter properties), so the same file runs on
 * Deno (Edge Functions) and on Node's type stripping (`node --test`, see engine.test.ts).
 */

export type MaintenanceStatus = "ok" | "due_soon" | "overdue";
export type RateSource = "ewma" | "baseline" | "none";
export type DueTrigger = "calendar" | "usage";

/** One odometer reading. */
export interface OdometerPoint {
  km: number;
  at: Date;
}

/** A trigger only counts when its interval and its "last done" value are both set. */
export interface ScheduleTerms {
  intervalDays: number | null;
  intervalKm: number | null;
  lastDoneAt: Date | null;
  lastDoneKm: number | null;
}

export interface UsageInput {
  readings: OdometerPoint[];
  currentOdometerKm: number | null;
  weeklyKmBaseline: number | null;
}

export interface UsageRate {
  /** null when source is "none". */
  kmPerDay: number | null;
  source: RateSource;
  confidence: number;
  /** Reading intervals the EWMA used (0 for baseline / none). */
  intervals: number;
}

export interface DuePrediction {
  nextDueAt: Date | null;
  nextDueKm: number | null;
  status: MaintenanceStatus;
  confidence: number;
  trigger: DueTrigger | null;
  /** ± half-width of the due window in days; 0 for exact dates, null when undated. */
  uncertaintyDays: number | null;
  rate: UsageRate;
  projectedKm: number | null;
}

export const HALF_LIFE_DAYS = 30.0;
export const RECENCY_HALF_LIFE_DAYS = 30.0;
export const MIN_INTERVAL_DAYS = 1.0;
export const BASELINE_CONFIDENCE = 0.3;
export const DUE_SOON_DAYS = 14.0;
export const DUE_SOON_FRACTION = 0.1;

const DAY_MS = 86_400_000.0;

export function evaluate(terms: ScheduleTerms, usage: UsageInput, now: Date, rate?: UsageRate): DuePrediction {
  const r = rate ?? usageRate(usage, now);
  const nowMs = now.getTime();
  const kmPerDay = r.kmPerDay !== null && r.kmPerDay > 0 ? r.kmPerDay : null;
  const anchorPoint = anchor(usage, now);

  const calendarDueMs = terms.intervalDays !== null && terms.lastDoneAt !== null
    ? terms.lastDoneAt.getTime() + terms.intervalDays * DAY_MS
    : null;

  const nextDueKm = terms.intervalKm !== null && terms.lastDoneKm !== null ? terms.lastDoneKm + terms.intervalKm : null;
  const projectedKm = anchorPoint === null
    ? null
    : anchorPoint.km + (kmPerDay ?? 0) * Math.max(0, (nowMs - anchorPoint.at.getTime()) / DAY_MS);

  let usageDueMs: number | null = null;
  let usageConfidence = 0;
  if (nextDueKm !== null && anchorPoint !== null) {
    const remainingAtAnchor = nextDueKm - anchorPoint.km;
    const anchorMs = anchorPoint.at.getTime();
    if (remainingAtAnchor <= 0) {
      // A recorded reading is already past the due km: overdue is a fact, not a forecast.
      usageDueMs = kmPerDay !== null ? anchorMs + (remainingAtAnchor / kmPerDay) * DAY_MS : anchorMs;
      usageConfidence = 1;
    } else if (kmPerDay !== null) {
      usageDueMs = anchorMs + (remainingAtAnchor / kmPerDay) * DAY_MS;
      usageConfidence = r.confidence;
    }
  }

  let trigger: DueTrigger | null = null;
  if (calendarDueMs !== null && usageDueMs !== null) trigger = calendarDueMs <= usageDueMs ? "calendar" : "usage";
  else if (calendarDueMs !== null) trigger = "calendar";
  else if (usageDueMs !== null) trigger = "usage";

  const nextDueMs = trigger === "calendar" ? calendarDueMs : trigger === "usage" ? usageDueMs : null;
  const confidence = trigger === "calendar" ? 1 : trigger === "usage" ? usageConfidence : 0;

  let status: MaintenanceStatus;
  if (nextDueMs !== null && nextDueMs <= nowMs) status = "overdue";
  else if (isDueSoon(nowMs, nextDueMs, calendarDueMs, terms, nextDueKm, projectedKm)) status = "due_soon";
  else status = "ok";

  let uncertaintyDays: number | null;
  if (trigger === null || nextDueMs === null) uncertaintyDays = null;
  else if (confidence >= 1) uncertaintyDays = 0;
  else uncertaintyDays = Math.ceil((Math.abs(nextDueMs - nowMs) / DAY_MS) * (1 - confidence));

  return {
    nextDueAt: nextDueMs === null ? null : toSecondDate(nextDueMs),
    nextDueKm,
    status,
    confidence,
    trigger,
    uncertaintyDays,
    rate: r,
    projectedKm: projectedKm === null ? null : Math.round(projectedKm),
  };
}

/** Time-decay EWMA of km/day, or the weekly baseline when there's no usable interval. */
export function usageRate(usage: UsageInput, now: Date): UsageRate {
  const nowMs = now.getTime();
  const sorted = [...usage.readings].sort(compareReadings);
  const pairs = intervals(sorted);
  if (pairs.length === 0) return baseline(usage.weeklyKmBaseline);

  let sumW = 0;
  let sumWR = 0;
  const weighted: Array<[number, number]> = pairs.map(([from, to]) => {
    const fromMs = from.at.getTime();
    const toMs = to.at.getTime();
    const days = (toMs - fromMs) / DAY_MS;
    const rate = (to.km - from.km) / days;
    const ageDays = Math.max(0, (nowMs - (fromMs + toMs) / 2) / DAY_MS);
    const w = days * Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    sumW += w;
    sumWR += w * rate;
    return [w, rate];
  });
  const mean = sumWR / sumW;
  let sumVar = 0;
  for (const [w, r] of weighted) sumVar += w * (r - mean) * (r - mean);
  const cv = mean > 0 ? Math.sqrt(sumVar / sumW) / mean : 0;

  const sinceLastDays = Math.max(0, (nowMs - sorted[sorted.length - 1].at.getTime()) / DAY_MS);
  const confidence = (1 - Math.pow(0.5, pairs.length)) *
    (1 / (1 + cv)) *
    Math.pow(0.5, sinceLastDays / RECENCY_HALF_LIFE_DAYS);

  return { kmPerDay: mean, source: "ewma", confidence: clamp01(confidence), intervals: pairs.length };
}

function baseline(weeklyKm: number | null): UsageRate {
  if (weeklyKm !== null && weeklyKm > 0) {
    return { kmPerDay: weeklyKm / 7, source: "baseline", confidence: BASELINE_CONFIDENCE, intervals: 0 };
  }
  return { kmPerDay: null, source: "none", confidence: 0, intervals: 0 };
}

/** Pairs of readings the rate is computed from. `sorted` is ordered by time, then km. */
function intervals(sorted: OdometerPoint[]): Array<[OdometerPoint, OdometerPoint]> {
  if (sorted.length < 2) return [];
  const out: Array<[OdometerPoint, OdometerPoint]> = [];
  let start = sorted[0];
  for (const reading of sorted.slice(1)) {
    if (reading.km < start.km) {
      start = reading; // odometer replaced or corrected: start over
    } else if ((reading.at.getTime() - start.at.getTime()) / DAY_MS < MIN_INTERVAL_DAYS) {
      // too close to measure a rate
    } else {
      out.push([start, reading]);
      start = reading;
    }
  }
  return out;
}

/**
 * Where the usage projection starts: the latest reading, unless the asset's odometer field is ahead of it
 * (or there are no readings), in which case that value counts as read just now.
 */
export function anchor(usage: UsageInput, now: Date): OdometerPoint | null {
  let latest: OdometerPoint | null = null;
  for (const r of usage.readings) if (latest === null || compareReadings(r, latest) > 0) latest = r;
  const current = usage.currentOdometerKm;
  if (latest === null) return current === null ? null : { km: current, at: now };
  if (current !== null && current > latest.km) return { km: current, at: now };
  return latest;
}

function isDueSoon(
  nowMs: number,
  nextDueMs: number | null,
  calendarDueMs: number | null,
  terms: ScheduleTerms,
  nextDueKm: number | null,
  projectedKm: number | null,
): boolean {
  if (nextDueMs !== null && nextDueMs - nowMs <= DUE_SOON_DAYS * DAY_MS) return true;
  if (
    calendarDueMs !== null && terms.intervalDays !== null &&
    calendarDueMs - nowMs <= DUE_SOON_FRACTION * terms.intervalDays * DAY_MS
  ) {
    return true;
  }
  return nextDueKm !== null && projectedKm !== null && terms.intervalKm !== null &&
    nextDueKm - projectedKm <= DUE_SOON_FRACTION * terms.intervalKm;
}

function compareReadings(a: OdometerPoint, b: OdometerPoint): number {
  return a.at.getTime() - b.at.getTime() || a.km - b.km;
}

function clamp01(x: number): number {
  return Math.min(1, Math.max(0, x));
}

/** Kotlin's `Instant.ofEpochSecond(Math.round(ms / 1000.0))`; JS Math.round also rounds half up. */
function toSecondDate(ms: number): Date {
  return new Date(Math.round(ms / 1000) * 1000);
}
