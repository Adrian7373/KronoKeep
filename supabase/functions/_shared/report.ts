/**
 * Pure helpers for verified reports (generate-report, verify-report, pdf.ts): the hashed snapshot,
 * canonical JSON, formatting for the PDF, and verify/share decisions. No I/O; tested in report.test.ts.
 */

// ---------------------------------------------------------------------------
// Rows (as PostgREST returns them)
// ---------------------------------------------------------------------------

export type AssetCategory = "vehicle" | "appliance" | "electronic";

export interface ReportAssetRow {
  id: string;
  category: AssetCategory;
  name: string;
  brand: string | null;
  model: string | null;
  serial_no: string | null;
  purchase_date: string | null;
  current_odometer_km: number | null;
}

export interface ReportScheduleRow {
  id: string;
  task: string;
  interval_days: number | null;
  interval_km: number | null;
  last_done_at: string | null;
  last_done_km: number | null;
  deleted_at: string | null;
}

export interface ReportLogRow {
  id: string;
  schedule_id: string | null;
  performed_at: string;
  odometer_km: number | null;
  cost_centavos: number | null;
  shop: string | null;
  notes: string | null;
  deleted_at: string | null;
}

/** The subset of a `reports` row the verify/share logic needs. */
export interface ReportStatusRow {
  revoked_at: string | null;
  share_token_hash?: string | null;
  share_expires_at?: string | null;
}

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

export interface SnapshotAsset {
  id: string;
  category: AssetCategory;
  name: string;
  brand: string | null;
  model: string | null;
  serial_no: string | null;
  purchase_date: string | null;
  current_odometer_km: number | null;
}

export interface SnapshotLog {
  id: string;
  performed_at: string;
  /** Schedule task name; null for an ad-hoc repair. */
  task: string | null;
  odometer_km: number | null;
  cost_centavos: number | null;
  shop: string | null;
  notes: string | null;
}

export interface SnapshotSchedule {
  id: string;
  task: string;
  interval_days: number | null;
  interval_km: number | null;
  last_done_at: string | null;
  last_done_km: number | null;
}

export interface ReportSnapshot {
  version: 1;
  asset: SnapshotAsset;
  service_logs: SnapshotLog[];
  schedules: SnapshotSchedule[];
}

/**
 * What the report attests to. Only strings, integers and null. Price and predictions are left out on
 * purpose (price is private; predictions change hourly). Report id and generated_at are left out too, so
 * identical content hashes identically and generate-report can reuse the existing report.
 */
export function buildSnapshot(
  asset: ReportAssetRow,
  schedules: ReportScheduleRow[],
  logs: ReportLogRow[],
): ReportSnapshot {
  // Deleted schedules still name the logs done under them.
  const taskById = new Map(schedules.map((s) => [s.id, s.task]));
  const serviceLogs = logs
    .filter((l) => l.deleted_at == null)
    .map((l): SnapshotLog => ({
      id: l.id,
      performed_at: isoMillis(l.performed_at),
      task: l.schedule_id == null ? null : taskById.get(l.schedule_id) ?? null,
      odometer_km: l.odometer_km ?? null,
      cost_centavos: l.cost_centavos ?? null,
      shop: l.shop ?? null,
      notes: l.notes ?? null,
    }))
    .sort((a, b) => cmp(a.performed_at, b.performed_at) || cmp(a.id, b.id));
  const liveSchedules = schedules
    .filter((s) => s.deleted_at == null)
    .map((s): SnapshotSchedule => ({
      id: s.id,
      task: s.task,
      interval_days: s.interval_days ?? null,
      interval_km: s.interval_km ?? null,
      last_done_at: s.last_done_at == null ? null : isoMillis(s.last_done_at),
      last_done_km: s.last_done_km ?? null,
    }))
    .sort((a, b) => cmp(a.task, b.task) || cmp(a.id, b.id));
  return {
    version: 1,
    asset: {
      id: asset.id,
      category: asset.category,
      name: asset.name,
      brand: asset.brand ?? null,
      model: asset.model ?? null,
      serial_no: asset.serial_no ?? null,
      purchase_date: asset.purchase_date ?? null,
      current_odometer_km: asset.current_odometer_km ?? null,
    },
    service_logs: serviceLogs,
    schedules: liveSchedules,
  };
}

const TIMESTAMP_RE =
  /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$/i;

/**
 * Epoch millis of a timestamptz as PostgREST (`2026-11-01T02:00:00.123456+00:00`) or psql
 * (`2026-11-01 02:00:00.123456+00`) prints it. Sub-millisecond digits are truncated. JS `Date` rejects
 * some of these forms (a bare `+00`), so parse explicitly. No offset means UTC. NaN when unparseable.
 */
export function parseInstantMs(timestamp: string): number {
  const m = TIMESTAMP_RE.exec(timestamp.trim());
  if (!m) return Number.NaN;
  const [, y, mo, d, h, mi, s, frac, zone] = m;
  const ms = Number((frac ?? "").slice(0, 3).padEnd(3, "0"));
  let offsetMin = 0;
  if (zone && zone.toUpperCase() !== "Z") {
    const digits = zone.slice(1).replace(":", "");
    offsetMin = (Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2) || "0")) * (zone[0] === "-" ? -1 : 1);
  }
  const utc = Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s ?? "0"), ms);
  return utc - offsetMin * 60_000;
}

function toDate(timestamp: string): Date {
  const ms = parseInstantMs(timestamp);
  if (Number.isNaN(ms)) throw new Error(`invalid timestamp: ${timestamp}`);
  return new Date(ms);
}

/** Postgres timestamptz text (any offset, microseconds) → ISO UTC with millis. */
export function isoMillis(timestamp: string): string {
  return toDate(timestamp).toISOString();
}

/** Code-unit order, locale independent (so Kotlin/TS/Postgres agree). */
function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

// ---------------------------------------------------------------------------
// Canonical JSON + hashing
// ---------------------------------------------------------------------------

/** Sorted keys at every level, no whitespace, JSON.stringify escaping. Only integers are allowed. */
export function canonicalJson(value: unknown): string {
  if (value === null) return "null";
  switch (typeof value) {
    case "string":
      return JSON.stringify(value);
    case "boolean":
      return value ? "true" : "false";
    case "number":
      if (!Number.isSafeInteger(value)) throw new Error(`canonicalJson: non-integer number ${value}`);
      return String(value);
    case "object": {
      if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
      const obj = value as Record<string, unknown>;
      const keys = Object.keys(obj).sort();
      return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`).join(",")}}`;
    }
    default:
      throw new Error(`canonicalJson: unsupported ${typeof value}`);
  }
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function timingSafeEqual(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function isUuid(s: string | null | undefined): s is string {
  return typeof s === "string" && UUID_RE.test(s);
}

// ---------------------------------------------------------------------------
// Formatting (PDF + verify text). Helvetica is WinAnsi-only, so pesos are "PHP", not ₱.
// ---------------------------------------------------------------------------

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export function groupThousands(n: number): string {
  const s = String(Math.abs(Math.trunc(n)));
  const grouped = s.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return n < 0 ? `-${grouped}` : grouped;
}

/** 123450 → "PHP 1,234.50". */
export function formatPesos(centavos: number): string {
  if (!Number.isSafeInteger(centavos)) throw new Error(`invalid centavos: ${centavos}`);
  const abs = Math.abs(centavos);
  const body = `${groupThousands(Math.floor(abs / 100))}.${String(abs % 100).padStart(2, "0")}`;
  return centavos < 0 ? `-PHP ${body}` : `PHP ${body}`;
}

/** 12345 → "12,345 km". */
export function formatKm(km: number): string {
  return `${groupThousands(km)} km`;
}

function manilaParts(timestamp: string) {
  const d = toDate(timestamp);
  const parts = new Intl.DateTimeFormat("en-PH", {
    timeZone: "Asia/Manila",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(d);
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  return { year: get("year"), month: get("month"), day: get("day"), hour: get("hour"), minute: get("minute") };
}

/** Instant → "15 Nov 2026" in Asia/Manila. */
export function formatDate(timestamp: string): string {
  const p = manilaParts(timestamp);
  return `${p.day} ${MONTHS[p.month - 1]} ${p.year}`;
}

/** Instant → "15 Nov 2026, 14:05 PHT". */
export function formatDateTime(timestamp: string): string {
  const p = manilaParts(timestamp);
  const hh = String(p.hour % 24).padStart(2, "0");
  const mm = String(p.minute).padStart(2, "0");
  return `${p.day} ${MONTHS[p.month - 1]} ${p.year}, ${hh}:${mm} PHT`;
}

/** Plain date "2026-11-15" → "15 Nov 2026" (no time zone involved). */
export function formatLocalDate(date: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) throw new Error(`invalid date: ${date}`);
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}`;
}

export function categoryLabel(category: AssetCategory): string {
  return { vehicle: "Vehicle", appliance: "Appliance", electronic: "Electronic" }[category] ?? category;
}

/** "Every 1,500 km or 60 days". */
export function formatInterval(intervalDays: number | null, intervalKm: number | null): string {
  const parts: string[] = [];
  if (intervalKm != null) parts.push(formatKm(intervalKm));
  if (intervalDays != null) parts.push(`${groupThousands(intervalDays)} ${intervalDays === 1 ? "day" : "days"}`);
  return parts.length === 0 ? "-" : `Every ${parts.join(" or ")}`;
}

/** "15 Nov 2026 at 12,000 km", or "-" when never done. */
export function formatLastDone(lastDoneAt: string | null, lastDoneKm: number | null): string {
  if (lastDoneAt == null && lastDoneKm == null) return "-";
  if (lastDoneAt == null) return `At ${formatKm(lastDoneKm!)}`;
  return lastDoneKm == null ? formatDate(lastDoneAt) : `${formatDate(lastDoneAt)} at ${formatKm(lastDoneKm)}`;
}

// ---------------------------------------------------------------------------
// WinAnsi sanitiser + word wrap
// ---------------------------------------------------------------------------

/** Non-Latin-1 characters that WinAnsi (CP1252) does encode, i.e. that StandardFonts.Helvetica can draw. */
const CP1252_EXTRA = new Set("€‚ƒ„…†‡ˆ‰Š‹ŒŽ•˜™š›œžŸ");

const REPLACEMENTS: Record<string, string> = {
  "‘": "'", "’": "'", "‚": "'", "‛": "'", "′": "'",
  "“": '"', "”": '"', "„": '"', "‟": '"', "″": '"',
  "‐": "-", "‑": "-", "‒": "-", "–": "-", "—": "-", "―": "-", "−": "-",
  " ": " ", " ": " ", " ": " ", " ": " ", " ": " ", " ": " ", " ": " ",
  "​": "", "‌": "", "‍": "", "﻿": "", "­": "",
  "₱": "PHP ",
  "\t": " ",
};

/**
 * Makes text drawable with Helvetica: NFC, typographic quotes/dashes → ASCII, ₱ → "PHP ", newlines kept
 * (as \n), other control characters → space, anything else WinAnsi can't encode → "?".
 */
export function sanitizeWinAnsi(text: string): string {
  let out = "";
  for (const ch of text.normalize("NFC").replace(/\r\n?/g, "\n")) {
    const replacement = REPLACEMENTS[ch];
    if (replacement !== undefined) {
      out += replacement;
      continue;
    }
    const cp = ch.codePointAt(0)!;
    if (ch === "\n" || (cp >= 0x20 && cp <= 0x7e) || (cp >= 0xa0 && cp <= 0xff) || CP1252_EXTRA.has(ch)) {
      out += ch;
    } else if (cp < 0x20 || (cp >= 0x7f && cp < 0xa0)) {
      out += " ";
    } else {
      out += "?";
    }
  }
  return out;
}

/**
 * Greedy word wrap. Honours \n, collapses runs of spaces, and breaks a word wider than [maxWidth] by
 * character. Always returns at least one line.
 */
export function wrapText(text: string, maxWidth: number, measure: (text: string) => number): string[] {
  const lines: string[] = [];
  for (const paragraph of text.split("\n")) {
    const words = paragraph.split(" ").filter((w) => w.length > 0);
    let line = "";
    for (const word of words) {
      const candidate = line === "" ? word : `${line} ${word}`;
      if (measure(candidate) <= maxWidth) {
        line = candidate;
        continue;
      }
      if (line !== "") lines.push(line);
      line = "";
      if (measure(word) <= maxWidth) {
        line = word;
        continue;
      }
      // Hard-break an over-long word (serials, URLs).
      for (const ch of word) {
        if (line !== "" && measure(line + ch) > maxWidth) {
          lines.push(line);
          line = "";
        }
        line += ch;
      }
    }
    lines.push(line);
  }
  return lines;
}

// ---------------------------------------------------------------------------
// Verify / share
// ---------------------------------------------------------------------------

export type VerifyStatus = "valid" | "revoked" | "not_found";

export function verifyStatus(row: ReportStatusRow | null | undefined): VerifyStatus {
  if (row == null) return "not_found";
  return row.revoked_at == null ? "valid" : "revoked";
}

/** A share link works while the report is live, the token hash matches, and now < share_expires_at. */
export function shareAllowed(row: ReportStatusRow | null | undefined, tokenHash: string, now: Date): boolean {
  if (verifyStatus(row) !== "valid") return false;
  const stored = row!.share_token_hash;
  const expires = row!.share_expires_at;
  if (!stored || !expires) return false;
  if (!timingSafeEqual(stored, tokenHash)) return false;
  const expiresMs = parseInstantMs(expires);
  return Number.isFinite(expiresMs) && now.getTime() < expiresMs;
}

/** Row fields verify-report exposes publicly. */
export interface VerifiableReport {
  id: string;
  generated_at: string;
  revoked_at: string | null;
  content_hash: string;
  log_count: number;
  snapshot: ReportSnapshot;
}

export interface VerifyResponse {
  status: VerifyStatus;
  report_id?: string;
  generated_at?: string;
  revoked_at?: string | null;
  content_hash?: string;
  asset?: { name: string; category: AssetCategory; brand: string | null; model: string | null };
  log_count?: number;
  service_logs?: Array<{ performed_at: string; task: string | null; odometer_km: number | null }>;
}

/** Public JSON. Shop, notes, cost and serial number stay private. */
export function verifyResponse(report: VerifiableReport | null): VerifyResponse {
  if (report == null) return { status: "not_found" };
  const { asset, service_logs } = report.snapshot;
  return {
    status: verifyStatus(report),
    report_id: report.id,
    generated_at: report.generated_at,
    revoked_at: report.revoked_at,
    content_hash: report.content_hash,
    asset: { name: asset.name, category: asset.category, brand: asset.brand, model: asset.model },
    log_count: report.log_count,
    service_logs: service_logs.map((l) => ({ performed_at: l.performed_at, task: l.task, odometer_km: l.odometer_km })),
  };
}

export const AD_HOC_TASK = "Repair / other";

/** Plain-text page for people who open the QR link in a browser. */
export function verifyText(response: VerifyResponse): string {
  if (response.status === "not_found") {
    return [
      "NOT FOUND",
      "",
      "No KronoKeep report has this ID. The document may have been altered, or the link mistyped.",
      "",
    ].join("\n");
  }
  const lines = response.status === "valid"
    ? ["VALID", "", "This report is on record with KronoKeep and has not been revoked. Compare the details below with the printed PDF."]
    : [
      "REVOKED",
      "",
      `The owner revoked this report on ${formatDateTime(response.revoked_at!)}. It may be out of date; don't rely on it.`,
    ];
  const a = response.asset!;
  const makeModel = [a.brand, a.model].filter((x) => x).join(" ");
  lines.push(
    "",
    `Report:     ${response.report_id}`,
    `Generated:  ${formatDateTime(response.generated_at!)}`,
    `SHA-256:    ${response.content_hash}`,
    `Asset:      ${a.name} (${categoryLabel(a.category)})${makeModel ? ` - ${makeModel}` : ""}`,
    `Entries:    ${response.log_count}`,
    "",
    "Service history:",
  );
  for (const l of response.service_logs ?? []) {
    const km = l.odometer_km == null ? "" : `  ${formatKm(l.odometer_km)}`;
    lines.push(`  ${formatDate(l.performed_at)}  ${l.task ?? AD_HOC_TASK}${km}`);
  }
  lines.push("");
  return lines.join("\n");
}
