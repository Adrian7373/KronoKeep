// generate-report: builds a verified maintenance PDF for one of the caller's assets.
//
// POST {"asset_id": uuid} with the user's access token (JWT verification stays on).
// 1. Loads the asset, its schedules (deleted ones too, for task names) and live service logs with the
//    service role, scoped to the caller.
// 2. Snapshot → canonical JSON → SHA-256 (_shared/report.ts). A live report with the same hash is reused.
// 3. Renders the PDF (_shared/pdf.ts), uploads it to reports/{user}/{asset}/{id}.pdf, then calls
//    finalize_report, which re-checks the source rows, locks the logs and inserts the row atomically.
//    Any failure after the upload removes the object.
//
// 200 {id, asset_id, storage_path, content_hash, generated_at, log_count, reused}
// Errors {error, message}: unauthorized 401, not_found 404, no_service_logs 422, changed 409, internal 500.
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import { renderReportPdf } from "../_shared/pdf.ts";
import {
  buildSnapshot,
  canonicalJson,
  isUuid,
  type ReportAssetRow,
  type ReportLogRow,
  type ReportScheduleRow,
  sha256Hex,
} from "../_shared/report.ts";

const PAGE = 1000;
const BUCKET = "reports";

interface ReportRow {
  id: string;
  asset_id: string;
  storage_path: string;
  content_hash: string;
  generated_at: string;
  log_count: number;
}

type LogWithVersion = ReportLogRow & { updated_at: string };

Deno.serve(async (req) => {
  if (req.method !== "POST") return error("method_not_allowed", "Use POST.", 405);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const db = createClient(supabaseUrl, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const jwt = /^Bearer\s+(.+)$/i.exec(req.headers.get("Authorization") ?? "")?.[1]?.trim();
  if (!jwt) return error("unauthorized", "Sign in to generate a report.", 401);
  const { data: auth, error: authError } = await db.auth.getUser(jwt);
  const userId = auth?.user?.id;
  if (authError || !userId) return error("unauthorized", "Sign in to generate a report.", 401);

  let assetId: unknown;
  try {
    assetId = (await req.json())?.asset_id;
  } catch {
    assetId = undefined;
  }
  if (typeof assetId !== "string" || !isUuid(assetId)) return error("not_found", "Asset not found.", 404);

  let uploadedPath: string | null = null;
  try {
    const { data: asset, error: assetError } = await db.from("assets")
      .select("id,category,name,brand,model,serial_no,purchase_date,current_odometer_km,updated_at")
      .eq("id", assetId).eq("user_id", userId).is("deleted_at", null)
      .maybeSingle<ReportAssetRow & { updated_at: string }>();
    if (assetError) throw assetError;
    if (!asset) return error("not_found", "Asset not found.", 404);

    const schedules = await pageAll<ReportScheduleRow>(db, "maintenance_schedules",
      "id,task,interval_days,interval_km,last_done_at,last_done_km,deleted_at", assetId, userId, false);
    const logs = await pageAll<LogWithVersion>(db, "service_logs",
      "id,schedule_id,performed_at,odometer_km,cost_centavos,shop,notes,deleted_at,updated_at", assetId, userId, true);
    if (logs.length === 0) {
      return error("no_service_logs", "Log at least one service before generating a report.", 422);
    }

    const snapshot = buildSnapshot(asset, schedules, logs);
    const canonical = canonicalJson(snapshot);
    const contentHash = await sha256Hex(canonical);

    const existing = await findLive(db, assetId, userId, contentHash);
    if (existing) return ok(existing, true);

    const id = crypto.randomUUID();
    const generatedAt = new Date().toISOString();
    const storagePath = `${userId}/${assetId}/${id}.pdf`;
    const pdf = await renderReportPdf({
      reportId: id,
      generatedAt,
      contentHash,
      snapshot,
      verifyUrl: `${supabaseUrl}/functions/v1/verify-report?id=${id}`,
    });

    const { error: uploadError } = await db.storage.from(BUCKET)
      .upload(storagePath, pdf, { contentType: "application/pdf", upsert: false });
    if (uploadError) throw uploadError;
    uploadedPath = storagePath;

    // updated_at values are passed through exactly as PostgREST returned them (microseconds).
    const { error: finalizeError } = await db.rpc("finalize_report", {
      p: {
        id,
        user_id: userId,
        asset_id: assetId,
        storage_path: storagePath,
        content_hash: contentHash,
        snapshot: canonical,
        log_count: logs.length,
        generated_at: generatedAt,
        asset_seen_updated_at: asset.updated_at,
        logs: logs.map((l) => ({ id: l.id, seen_updated_at: l.updated_at })),
      },
    });
    if (finalizeError) {
      await removeUpload(db, storagePath);
      uploadedPath = null;
      if (finalizeError.code === "40001") {
        return error("changed", "The asset or its service history changed while the report was being made. Try again.", 409);
      }
      if (finalizeError.code === "23505") {
        // A concurrent request finalized the same content first: hand back that report.
        const winner = await findLive(db, assetId, userId, contentHash);
        if (winner) return ok(winner, true);
      }
      throw finalizeError;
    }

    return ok({
      id,
      asset_id: assetId,
      storage_path: storagePath,
      content_hash: contentHash,
      generated_at: generatedAt,
      log_count: logs.length,
    }, false);
  } catch (e) {
    if (uploadedPath) await removeUpload(db, uploadedPath);
    console.error("generate-report failed", e);
    return error("internal", "Couldn't generate the report. Try again later.", 500);
  }
});

async function findLive(db: SupabaseClient, assetId: string, userId: string, contentHash: string) {
  const { data, error } = await db.from("reports")
    .select("id,asset_id,storage_path,content_hash,generated_at,log_count")
    .eq("asset_id", assetId).eq("user_id", userId).eq("content_hash", contentHash).is("revoked_at", null)
    .order("generated_at", { ascending: false }).limit(1)
    .maybeSingle<ReportRow>();
  if (error) throw error;
  return data;
}

async function pageAll<T>(
  db: SupabaseClient,
  table: string,
  columns: string,
  assetId: string,
  userId: string,
  liveOnly: boolean,
): Promise<T[]> {
  const out: T[] = [];
  for (let from = 0;; from += PAGE) {
    let q = db.from(table).select(columns).eq("asset_id", assetId).eq("user_id", userId);
    if (liveOnly) q = q.is("deleted_at", null);
    const { data, error } = await q.order("id").range(from, from + PAGE - 1);
    if (error) throw error;
    out.push(...(data as T[]));
    if (data.length < PAGE) return out;
  }
}

async function removeUpload(db: SupabaseClient, path: string) {
  const { error } = await db.storage.from(BUCKET).remove([path]);
  if (error) console.error("orphan report object not removed", path, error);
}

function ok(row: ReportRow, reused: boolean): Response {
  return json({
    id: row.id,
    asset_id: row.asset_id,
    storage_path: row.storage_path,
    content_hash: row.content_hash,
    generated_at: row.generated_at,
    log_count: row.log_count,
    reused,
  }, 200);
}

function error(code: string, message: string, status: number): Response {
  return json({ error: code, message }, status);
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
