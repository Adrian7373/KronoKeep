/**
 * Pure planning for the `recompute-due` Edge Function: given rows straight from Postgres and `now`,
 * decide which schedules' stored due fields change and which reminders are candidates to send.
 * No I/O here, so it's tested with plain fixtures (recompute.test.ts).
 */
import { evaluate, type MaintenanceStatus, usageRate } from "./engine.ts";

export interface AssetRow {
  id: string;
  user_id: string;
  name: string;
  current_odometer_km: number | null;
  weekly_km_baseline: number | null;
  archived_at: string | null;
  deleted_at: string | null;
}

export interface ScheduleRow {
  id: string;
  user_id: string;
  asset_id: string;
  task: string;
  interval_days: number | null;
  interval_km: number | null;
  last_done_at: string | null;
  last_done_km: number | null;
  next_due_at: string | null;
  next_due_km: number | null;
  confidence: number | null;
  status: MaintenanceStatus;
  /** Passed back verbatim (microsecond precision) as the optimistic-concurrency check. */
  updated_at: string;
  deleted_at: string | null;
}

export interface ReadingRow {
  asset_id: string;
  km: number;
  recorded_at: string;
  deleted_at: string | null;
}

/** New due fields for one schedule; sent to `apply_schedule_due`. */
export interface DueUpdate {
  id: string;
  /** The row's `updated_at` when it was read; the update is skipped if a device edited it since. */
  seen_updated_at: string;
  next_due_at: string | null;
  next_due_km: number | null;
  confidence: number;
  status: MaintenanceStatus;
}

/** A schedule that is due soon or overdue. Whether it's actually sent is decided by `reminder_log`. */
export interface ReminderCandidate {
  user_id: string;
  schedule_id: string;
  asset_id: string;
  asset_name: string;
  task: string;
  status: "due_soon" | "overdue";
  next_due_at: string | null;
  /** Identifies one service cycle: a new log or an interval edit starts a new cycle, so it notifies again. */
  cycle_key: string;
}

export interface RecomputePlan {
  updates: DueUpdate[];
  reminders: ReminderCandidate[];
}

/** Same threshold as the app's ScheduleRecomputer, so the server doesn't churn rows the device wouldn't. */
export const CONFIDENCE_STEP = 0.05;

/** Reminders go out between 08:00 and 21:00 Philippine time (UTC+8, no DST). */
export const SEND_FROM_HOUR = 8;
export const SEND_UNTIL_HOUR = 21;
const MANILA_OFFSET_HOURS = 8;

export function planRecompute(
  assets: AssetRow[],
  schedules: ScheduleRow[],
  readings: ReadingRow[],
  now: Date,
): RecomputePlan {
  const liveAssets = new Map(assets.filter((a) => a.deleted_at === null).map((a) => [a.id, a]));
  const readingsByAsset = new Map<string, ReadingRow[]>();
  for (const r of readings) {
    if (r.deleted_at !== null) continue;
    const list = readingsByAsset.get(r.asset_id);
    if (list) list.push(r);
    else readingsByAsset.set(r.asset_id, [r]);
  }

  const schedulesByAsset = new Map<string, ScheduleRow[]>();
  for (const s of schedules) {
    if (s.deleted_at !== null || !liveAssets.has(s.asset_id)) continue;
    const list = schedulesByAsset.get(s.asset_id);
    if (list) list.push(s);
    else schedulesByAsset.set(s.asset_id, [s]);
  }

  const updates: DueUpdate[] = [];
  const reminders: ReminderCandidate[] = [];
  for (const [assetId, list] of schedulesByAsset) {
    const asset = liveAssets.get(assetId)!;
    const usage = {
      readings: (readingsByAsset.get(assetId) ?? []).map((r) => ({ km: r.km, at: new Date(r.recorded_at) })),
      currentOdometerKm: asset.current_odometer_km,
      weeklyKmBaseline: asset.weekly_km_baseline,
    };
    const rate = usageRate(usage, now); // one rate per asset, like the app
    for (const s of list) {
      const p = evaluate(
        {
          intervalDays: s.interval_days,
          intervalKm: s.interval_km,
          lastDoneAt: s.last_done_at === null ? null : new Date(s.last_done_at),
          lastDoneKm: s.last_done_km,
        },
        usage,
        now,
        rate,
      );
      const update: DueUpdate = {
        id: s.id,
        seen_updated_at: s.updated_at,
        next_due_at: p.nextDueAt?.toISOString() ?? null,
        next_due_km: p.nextDueKm,
        confidence: p.confidence,
        status: p.status,
      };
      if (meaningfullyDifferent(s, update)) updates.push(update);

      // Archived assets keep their due fields current but don't nag (the app filters them out too).
      if (p.status !== "ok" && asset.archived_at === null) {
        reminders.push({
          user_id: s.user_id,
          schedule_id: s.id,
          asset_id: assetId,
          asset_name: asset.name,
          task: s.task,
          status: p.status,
          next_due_at: update.next_due_at,
          cycle_key: cycleKey(s),
        });
      }
    }
  }
  return { updates, reminders };
}

export function cycleKey(s: Pick<ScheduleRow, "last_done_at" | "last_done_km" | "interval_days" | "interval_km">): string {
  const doneAt = s.last_done_at === null ? "" : new Date(s.last_done_at).toISOString();
  return [doneAt, s.last_done_km ?? "", s.interval_days ?? "", s.interval_km ?? ""].join("|");
}

function meaningfullyDifferent(old: ScheduleRow, next: DueUpdate): boolean {
  if (old.status !== next.status || old.next_due_km !== next.next_due_km) return true;
  if (!sameInstant(old.next_due_at, next.next_due_at)) return true;
  if (old.confidence === null) return true;
  // `confidence` is a Postgres real (float4): compare with a step, never for equality.
  return Math.abs(old.confidence - next.confidence) >= CONFIDENCE_STEP;
}

function sameInstant(a: string | null, b: string | null): boolean {
  if (a === null || b === null) return a === b;
  return Math.abs(new Date(a).getTime() - new Date(b).getTime()) < 1000;
}

/** True when it's a sensible hour to buzz a phone in the Philippines. */
export function inSendWindow(now: Date): boolean {
  const hour = (now.getUTCHours() + MANILA_OFFSET_HOURS) % 24;
  return hour >= SEND_FROM_HOUR && hour < SEND_UNTIL_HOUR;
}
