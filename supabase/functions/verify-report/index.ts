// verify-report: public endpoint behind the QR code and share links (JWT verification off in config.toml).
//
// GET ?id=<uuid>             Status of a report: valid / revoked / not_found. JSON when `format=json` or the
//                            Accept header asks for application/json, otherwise a plain-text summary.
//                            200 for valid/revoked, 404 for not_found.
// GET ?id=<uuid>&t=<token>   Share link: 302 to a 5-minute signed URL for the PDF while the token is current
//                            and the report isn't revoked, otherwise 410.
//
// The stored snapshot is re-hashed before answering; a row whose hash doesn't match is never shown as valid.
// Only non-private fields leave the server (no shop, notes, costs or serial number).
import { createClient } from "npm:@supabase/supabase-js@2";
import {
  isUuid,
  type ReportSnapshot,
  sha256Hex,
  shareAllowed,
  type VerifiableReport,
  verifyResponse,
  verifyText,
} from "../_shared/report.ts";

const SIGNED_URL_SECONDS = 300;

interface StoredReport {
  id: string;
  storage_path: string;
  content_hash: string;
  generated_at: string;
  revoked_at: string | null;
  snapshot: string;
  log_count: number;
  share_token_hash: string | null;
  share_expires_at: string | null;
}

Deno.serve(async (req) => {
  if (req.method !== "GET") return text("Method not allowed.\n", 405, { Allow: "GET" });

  const url = new URL(req.url);
  const id = url.searchParams.get("id");
  const token = url.searchParams.get("t");
  const wantsJson = url.searchParams.get("format") === "json" ||
    (req.headers.get("Accept") ?? "").toLowerCase().includes("application/json");

  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const row = isUuid(id) ? await load(db, id) : null;

    if (token !== null) {
      if (row && shareAllowed(row, await sha256Hex(token), new Date())) {
        const { data, error } = await db.storage.from("reports").createSignedUrl(row.storage_path, SIGNED_URL_SECONDS, {
          download: `KronoKeep-report-${row.id.slice(0, 8)}.pdf`,
        });
        if (error || !data?.signedUrl) throw error ?? new Error("no signed URL");
        return new Response(null, {
          status: 302,
          headers: { Location: data.signedUrl, "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" },
        });
      }
      return text("This link has expired or been revoked. Ask the owner for a new one.\n", 410);
    }

    const report: VerifiableReport | null = row
      ? {
        id: row.id,
        generated_at: row.generated_at,
        revoked_at: row.revoked_at,
        content_hash: row.content_hash,
        log_count: row.log_count,
        snapshot: JSON.parse(row.snapshot) as ReportSnapshot,
      }
      : null;
    const body = verifyResponse(report);
    const status = body.status === "not_found" ? 404 : 200;
    return wantsJson
      ? new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
      })
      : text(verifyText(body), status);
  } catch (e) {
    console.error("verify-report failed", e);
    return wantsJson
      ? new Response(JSON.stringify({ error: "internal" }), {
        status: 500,
        headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
      })
      : text("Verification is temporarily unavailable. Try again later.\n", 500);
  }
});

/** The report row, or null when missing or when its snapshot no longer matches its hash. */
async function load(db: ReturnType<typeof createClient>, id: string): Promise<StoredReport | null> {
  const { data, error } = await db.from("reports")
    .select("id,storage_path,content_hash,generated_at,revoked_at,snapshot,log_count,share_token_hash,share_expires_at")
    .eq("id", id)
    .maybeSingle<StoredReport>();
  if (error) throw error;
  if (!data) return null;
  if ((await sha256Hex(data.snapshot)) !== data.content_hash) {
    console.error("report snapshot does not match content_hash", id);
    return null;
  }
  return data;
}

function text(body: string, status: number, extra: Record<string, string> = {}): Response {
  return new Response(body, {
    status,
    headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store", ...extra },
  });
}
