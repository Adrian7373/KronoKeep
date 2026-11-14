// recompute-due: run hourly by pg_cron (see migration 20261115000000_server_engine.sql).
//
// 1. Re-runs the maintenance engine (_shared/engine.ts, same fixtures as the app) over every live schedule and
//    stores changed due fields through `apply_schedule_due`, which leaves `updated_at` alone so a server
//    recompute never wins last-write-wins against a user's edit.
// 2. Between 08:00 and 21:00 Manila time, pushes a data-only FCM message for each schedule that newly became
//    due soon / overdue, claimed in `reminder_log` so each one is sent once.
//
// Auth: not a user endpoint. JWT verification is off (supabase/config.toml) and callers must send the
// `x-cron-secret` header matching the CRON_SECRET function secret.
// Secrets: CRON_SECRET, FCM_SERVICE_ACCOUNT (service-account JSON; optional, pushes are skipped without it).
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided by the Edge runtime.
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import { deliverReminders, type DeliveryStats, type ReminderKey } from "../_shared/deliver.ts";
import { FcmClient } from "../_shared/fcm.ts";
import {
  type AssetRow,
  inSendWindow,
  planRecompute,
  type ReadingRow,
  type ScheduleRow,
} from "../_shared/recompute.ts";

const PAGE = 1000;
const IN_CHUNK = 200;
const UPDATE_CHUNK = 500;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  const secret = Deno.env.get("CRON_SECRET");
  if (!secret || !timingSafeEqual(req.headers.get("x-cron-secret") ?? "", secret)) {
    return json({ error: "unauthorized" }, 401);
  }

  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const now = new Date();

  try {
    const schedules = await pageAll<ScheduleRow>(db, "maintenance_schedules",
      "id,user_id,asset_id,task,interval_days,interval_km,last_done_at,last_done_km,next_due_at,next_due_km,confidence,status,updated_at,deleted_at");
    const assetIds = [...new Set(schedules.map((s) => s.asset_id))];
    const assets = await selectIn<AssetRow>(db, "assets",
      "id,user_id,name,current_odometer_km,weekly_km_baseline,archived_at,deleted_at", "id", assetIds, "id");
    const readings = await selectIn<ReadingRow>(db, "odometer_readings",
      "asset_id,km,recorded_at,deleted_at", "asset_id", assetIds, "id", true);

    const plan = planRecompute(assets, schedules, readings, now);
    for (const chunk of chunks(plan.updates, UPDATE_CHUNK)) {
      const { error } = await db.rpc("apply_schedule_due", { p_updates: chunk });
      if (error) throw error;
    }

    let delivery: DeliveryStats | "outside send window" | "fcm not configured" = "outside send window";
    if (inSendWindow(now)) {
      const fcm = FcmClient.fromEnv(Deno.env.get("FCM_SERVICE_ACCOUNT"));
      delivery = fcm ? await deliver(db, fcm, plan.reminders) : "fcm not configured";
    }

    return json({ now: now.toISOString(), schedules: schedules.length, updated: plan.updates.length, delivery });
  } catch (e) {
    console.error("recompute-due failed", e);
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});

async function deliver(db: SupabaseClient, fcm: FcmClient, reminders: ReturnType<typeof planRecompute>["reminders"]) {
  const userIds = [...new Set(reminders.map((r) => r.user_id))];
  const tokens = await selectIn<{ token: string; user_id: string }>(db, "device_tokens", "token,user_id", "user_id", userIds, "token");
  const tokensByUser = new Map<string, string[]>();
  for (const t of tokens) tokensByUser.set(t.user_id, [...(tokensByUser.get(t.user_id) ?? []), t.token]);

  return deliverReminders(reminders, tokensByUser, {
    async claim(rows) {
      const claimed: ReminderKey[] = [];
      for (const chunk of chunks(rows, UPDATE_CHUNK)) {
        // ON CONFLICT DO NOTHING RETURNING: only rows this run inserted come back.
        const { data, error } = await db.from("reminder_log")
          .upsert(chunk, { onConflict: "schedule_id,status,cycle_key", ignoreDuplicates: true })
          .select("schedule_id,status,cycle_key");
        if (error) throw error;
        claimed.push(...(data ?? []));
      }
      return claimed;
    },
    async release(key) {
      const { error } = await db.from("reminder_log").delete()
        .eq("schedule_id", key.schedule_id).eq("status", key.status).eq("cycle_key", key.cycle_key);
      if (error) console.error("release failed", error);
    },
    send: (token, data) => fcm.send(token, data),
    async deleteToken(token) {
      const { error } = await db.from("device_tokens").delete().eq("token", token);
      if (error) console.error("token delete failed", error);
    },
  });
}

async function pageAll<T>(db: SupabaseClient, table: string, columns: string): Promise<T[]> {
  const out: T[] = [];
  for (let from = 0;; from += PAGE) {
    const { data, error } = await db.from(table).select(columns).is("deleted_at", null)
      .order("id").range(from, from + PAGE - 1);
    if (error) throw error;
    out.push(...(data as T[]));
    if (data.length < PAGE) return out;
  }
}

async function selectIn<T>(
  db: SupabaseClient,
  table: string,
  columns: string,
  column: string,
  values: string[],
  uniqueKey: string,
  liveOnly = false,
): Promise<T[]> {
  const out: T[] = [];
  for (const chunk of chunks(values, IN_CHUNK)) {
    // Page inside each chunk too: one asset can have many readings.
    for (let from = 0;; from += PAGE) {
      let q = db.from(table).select(columns).in(column, chunk);
      if (liveOnly) q = q.is("deleted_at", null);
      const { data, error } = await q.order(uniqueKey).range(from, from + PAGE - 1);
      if (error) throw error;
      out.push(...(data as T[]));
      if (data.length < PAGE) break;
    }
  }
  return out;
}

function chunks<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

function timingSafeEqual(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
