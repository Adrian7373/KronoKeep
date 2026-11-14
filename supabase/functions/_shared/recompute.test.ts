// node --test supabase/functions/_shared/   (or deno test --allow-read supabase/functions/_shared/)
import { test } from "node:test";
import assert from "node:assert/strict";
import { type AssetRow, cycleKey, inSendWindow, planRecompute, type ReadingRow, type ScheduleRow } from "./recompute.ts";

const NOW = new Date("2026-11-15T02:00:00Z"); // 10:00 in Manila

const asset = (over: Partial<AssetRow> = {}): AssetRow => ({
  id: "a1",
  user_id: "u1",
  name: "Click 125",
  current_odometer_km: 10_000,
  weekly_km_baseline: 350,
  archived_at: null,
  deleted_at: null,
  ...over,
});

const schedule = (over: Partial<ScheduleRow> = {}): ScheduleRow => ({
  id: "s1",
  user_id: "u1",
  asset_id: "a1",
  task: "Engine oil",
  interval_days: 60,
  interval_km: null,
  last_done_at: "2026-10-01T00:00:00Z",
  last_done_km: null,
  // What the engine produces for these terms: calendar due 2026-11-30, 14.9 days out → ok.
  next_due_at: "2026-11-30T00:00:00Z",
  next_due_km: null,
  confidence: 1,
  status: "ok",
  updated_at: "2026-10-01T00:00:00.123456+00:00",
  deleted_at: null,
  ...over,
});

test("unchanged prediction produces no update and no reminder", () => {
  const plan = planRecompute([asset()], [schedule()], [], NOW);
  assert.deepEqual(plan, { updates: [], reminders: [] });
});

test("status change produces an update carrying the seen updated_at verbatim", () => {
  const s = schedule({ last_done_at: "2026-09-20T00:00:00Z" }); // due 2026-11-19 → due soon
  const plan = planRecompute([asset()], [s], [], NOW);
  assert.equal(plan.updates.length, 1);
  assert.deepEqual(plan.updates[0], {
    id: "s1",
    seen_updated_at: "2026-10-01T00:00:00.123456+00:00",
    next_due_at: "2026-11-19T00:00:00.000Z",
    next_due_km: null,
    confidence: 1,
    status: "due_soon",
  });
  assert.equal(plan.reminders.length, 1);
  assert.deepEqual(
    { ...plan.reminders[0], cycle_key: undefined },
    {
      user_id: "u1",
      schedule_id: "s1",
      asset_id: "a1",
      asset_name: "Click 125",
      task: "Engine oil",
      status: "due_soon",
      next_due_at: "2026-11-19T00:00:00.000Z",
      cycle_key: undefined,
    },
  );
});

test("an already-stored due_soon still yields a reminder candidate (dedupe is reminder_log's job)", () => {
  const s = schedule({ last_done_at: "2026-09-20T00:00:00Z", next_due_at: "2026-11-19T00:00:00Z", status: "due_soon" });
  const plan = planRecompute([asset()], [s], [], NOW);
  assert.equal(plan.updates.length, 0);
  assert.equal(plan.reminders.length, 1);
});

test("confidence drift below the step is ignored, at or above it is written", () => {
  const terms = { interval_days: null, interval_km: 1500, last_done_at: null, last_done_km: 10_000 };
  const readings: ReadingRow[] = [
    { asset_id: "a1", km: 9_000, recorded_at: "2026-10-16T02:00:00Z", deleted_at: null },
    { asset_id: "a1", km: 10_000, recorded_at: "2026-11-05T02:00:00Z", deleted_at: null },
  ];
  const fresh = planRecompute([asset()], [schedule({ ...terms, next_due_at: null, confidence: null })], readings, NOW)
    .updates[0];
  assert.ok(fresh, "first computation is always written");
  const stored = { ...terms, next_due_at: fresh.next_due_at, next_due_km: fresh.next_due_km, status: fresh.status };
  assert.equal(planRecompute([asset()], [schedule({ ...stored, confidence: fresh.confidence + 0.04 })], readings, NOW)
    .updates.length, 0);
  // 0.051, not 0.05: (c + 0.05) - c can land a hair under the step in floating point.
  assert.equal(planRecompute([asset()], [schedule({ ...stored, confidence: fresh.confidence - 0.051 })], readings, NOW)
    .updates.length, 1);
});

test("deleted schedules, deleted assets and deleted readings are ignored", () => {
  const due = { last_done_at: "2026-09-01T00:00:00Z" }; // overdue
  assert.deepEqual(planRecompute([asset()], [schedule({ ...due, deleted_at: NOW.toISOString() })], [], NOW).updates, []);
  assert.deepEqual(planRecompute([asset({ deleted_at: NOW.toISOString() })], [schedule(due)], [], NOW).updates, []);

  // A deleted reading must not feed the rate: with only the baseline, km-only oil keeps baseline confidence.
  const kmOnly = { interval_days: null, interval_km: 1500, last_done_at: null, last_done_km: 10_000, next_due_at: null };
  const readings: ReadingRow[] = [
    { asset_id: "a1", km: 9_000, recorded_at: "2026-10-01T00:00:00Z", deleted_at: "2026-10-02T00:00:00Z" },
    { asset_id: "a1", km: 10_000, recorded_at: "2026-11-14T00:00:00Z", deleted_at: null },
  ];
  const plan = planRecompute([asset()], [schedule(kmOnly)], readings, NOW);
  assert.equal(plan.updates[0].confidence, 0.3);
});

test("archived assets get updates but no reminders", () => {
  const plan = planRecompute(
    [asset({ archived_at: "2026-11-01T00:00:00Z" })],
    [schedule({ last_done_at: "2026-09-01T00:00:00Z" })],
    [],
    NOW,
  );
  assert.equal(plan.updates[0].status, "overdue");
  assert.deepEqual(plan.reminders, []);
});

test("the cycle key changes when a service is logged or the interval changes", () => {
  const base = schedule();
  assert.notEqual(cycleKey(base), cycleKey({ ...base, last_done_at: "2026-11-15T00:00:00Z" }));
  assert.notEqual(cycleKey(base), cycleKey({ ...base, interval_days: 90 }));
  // Same instant in another notation is the same cycle.
  assert.equal(cycleKey(base), cycleKey({ ...base, last_done_at: "2026-10-01T08:00:00+08:00" }));
});

test("send window is 08:00 to 21:00 Manila time", () => {
  assert.equal(inSendWindow(new Date("2026-11-14T23:59:00Z")), false); // 07:59
  assert.equal(inSendWindow(new Date("2026-11-15T00:00:00Z")), true); // 08:00
  assert.equal(inSendWindow(new Date("2026-11-15T12:59:00Z")), true); // 20:59
  assert.equal(inSendWindow(new Date("2026-11-15T13:00:00Z")), false); // 21:00
});
