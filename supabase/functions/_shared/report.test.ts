import { test } from "node:test";
import assert from "node:assert/strict";
import {
  buildSnapshot,
  canonicalJson,
  formatDate,
  formatDateTime,
  formatInterval,
  formatKm,
  formatLastDone,
  formatLocalDate,
  formatPesos,
  isoMillis,
  isUuid,
  parseInstantMs,
  type ReportAssetRow,
  type ReportLogRow,
  type ReportScheduleRow,
  sanitizeWinAnsi,
  sha256Hex,
  shareAllowed,
  verifyResponse,
  verifyStatus,
  verifyText,
  wrapText,
} from "./report.ts";

const asset: ReportAssetRow = {
  id: "a1",
  category: "vehicle",
  name: "Click 125",
  brand: "Honda",
  model: "Click 125i",
  serial_no: "MH1JM1234",
  purchase_date: "2025-03-01",
  current_odometer_km: 12345,
};

const schedule = (over: Partial<ReportScheduleRow>): ReportScheduleRow => ({
  id: "s1",
  task: "Engine oil",
  interval_days: 60,
  interval_km: 1500,
  last_done_at: "2026-11-01 02:00:00.123456+00",
  last_done_km: 12000,
  deleted_at: null,
  ...over,
});

const log = (over: Partial<ReportLogRow>): ReportLogRow => ({
  id: "l1",
  schedule_id: "s1",
  performed_at: "2026-11-01T10:00:00.123456+08:00",
  odometer_km: 12000,
  cost_centavos: 45000,
  shop: "Moto Shop",
  notes: null,
  deleted_at: null,
  ...over,
});

// --- canonical JSON / hashing -------------------------------------------------

test("canonicalJson sorts keys at every level and has no whitespace", () => {
  const out = canonicalJson({ b: 1, a: { d: [3, { z: null, y: true }], c: "x" } });
  assert.equal(out, '{"a":{"c":"x","d":[3,{"y":true,"z":null}]},"b":1}');
});

test("canonicalJson uses JSON.stringify escaping", () => {
  assert.equal(canonicalJson({ s: 'q"\\\n\u0001é₱' }), '{"s":"q\\"\\\\\\n\\u0001é₱"}');
});

test("canonicalJson rejects floats, non-finite numbers and undefined", () => {
  assert.throws(() => canonicalJson({ x: 1.5 }));
  assert.throws(() => canonicalJson({ x: Number.NaN }));
  assert.throws(() => canonicalJson({ x: Infinity }));
  assert.throws(() => canonicalJson({ x: undefined }));
});

test("sha256Hex matches known vectors", async () => {
  assert.equal(await sha256Hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  assert.equal(await sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});

test("isUuid", () => {
  assert.equal(isUuid("0b3c2d4e-1f2a-4b5c-8d9e-0f1a2b3c4d5e"), true);
  assert.equal(isUuid("0B3C2D4E-1F2A-4B5C-8D9E-0F1A2B3C4D5E"), true);
  assert.equal(isUuid("not-a-uuid"), false);
  assert.equal(isUuid(null), false);
  assert.equal(isUuid("0b3c2d4e-1f2a-4b5c-8d9e-0f1a2b3c4d5e' or 1=1"), false);
});

test("parseInstantMs / isoMillis accept PostgREST and psql forms", () => {
  const want = "2026-11-01T02:00:00.123Z";
  for (const s of [
    "2026-11-01T02:00:00.123456+00:00",
    "2026-11-01 02:00:00.123456+00",
    "2026-11-01T10:00:00.123999+08:00", // truncated, not rounded
    "2026-11-01T10:00:00.123+0800",
    "2026-10-31T21:30:00.123-04:30",
    "2026-11-01T02:00:00.123Z",
    "2026-11-01T02:00:00.123",
  ]) assert.equal(isoMillis(s), want, s);
  assert.equal(isoMillis("2026-11-01T02:00:00+00:00"), "2026-11-01T02:00:00.000Z");
  assert.ok(Number.isNaN(parseInstantMs("garbage")));
  assert.ok(Number.isNaN(parseInstantMs("2026-11-01")));
  assert.throws(() => isoMillis("garbage"));
});

// --- snapshot -----------------------------------------------------------------

test("buildSnapshot sorts logs by performed_at then id and normalises timestamps", () => {
  const snap = buildSnapshot(asset, [schedule({})], [
    log({ id: "l3", performed_at: "2026-11-02T00:00:00Z" }),
    log({ id: "l2", performed_at: "2026-11-01 02:00:00.123456+00" }),
    log({ id: "l1", performed_at: "2026-11-01T10:00:00.123+08:00" }),
  ]);
  assert.deepEqual(snap.service_logs.map((l) => l.id), ["l1", "l2", "l3"]);
  assert.equal(snap.service_logs[0].performed_at, "2026-11-01T02:00:00.123Z");
  assert.equal(snap.service_logs[2].performed_at, "2026-11-02T00:00:00.000Z");
  assert.equal(snap.schedules[0].last_done_at, "2026-11-01T02:00:00.123Z");
});

test("buildSnapshot drops deleted rows, names logs from deleted schedules, and nulls ad-hoc tasks", () => {
  const snap = buildSnapshot(asset, [
    schedule({ id: "s1", task: "Engine oil" }),
    schedule({ id: "s0", task: "Old task", deleted_at: "2026-10-01T00:00:00Z" }),
  ], [
    log({ id: "l1", schedule_id: "s0" }),
    log({ id: "l2", schedule_id: null, performed_at: "2026-11-03T00:00:00Z" }),
    log({ id: "l3", schedule_id: "missing", performed_at: "2026-11-04T00:00:00Z" }),
    log({ id: "lx", deleted_at: "2026-11-05T00:00:00Z" }),
  ]);
  assert.deepEqual(snap.service_logs.map((l) => [l.id, l.task]), [["l1", "Old task"], ["l2", null], ["l3", null]]);
  assert.deepEqual(snap.schedules.map((s) => s.id), ["s1"]);
});

test("buildSnapshot sorts schedules by task then id and keeps only whitelisted fields", () => {
  const snap = buildSnapshot({ ...asset, price_centavos: 9_999_900 } as ReportAssetRow, [
    schedule({ id: "s2", task: "Gear oil" }),
    schedule({ id: "s9", task: "Air filter", last_done_at: null }),
    schedule({ id: "s1", task: "Air filter", last_done_at: null }),
  ], [log({ next_due_at: "x" } as Partial<ReportLogRow>)]);
  assert.deepEqual(snap.schedules.map((s) => s.id), ["s1", "s9", "s2"]);
  assert.equal("price_centavos" in snap.asset, false);
  assert.deepEqual(Object.keys(snap.service_logs[0]).sort(),
    ["cost_centavos", "id", "notes", "odometer_km", "performed_at", "shop", "task"]);
  assert.equal(snap.version, 1);
});

test("identical content hashes identically regardless of input order", async () => {
  const a = buildSnapshot(asset, [schedule({ id: "s1" }), schedule({ id: "s2", task: "Gear oil" })],
    [log({ id: "l1" }), log({ id: "l2", performed_at: "2026-11-03T00:00:00Z" })]);
  const b = buildSnapshot(asset, [schedule({ id: "s2", task: "Gear oil" }), schedule({ id: "s1" })],
    [log({ id: "l2", performed_at: "2026-11-03T00:00:00.000000+00" }), log({ id: "l1" })]);
  assert.equal(await sha256Hex(canonicalJson(a)), await sha256Hex(canonicalJson(b)));
  const c = buildSnapshot(asset, [schedule({ id: "s1" })], [log({ id: "l1", cost_centavos: 45001 })]);
  assert.notEqual(await sha256Hex(canonicalJson(a)), await sha256Hex(canonicalJson(c)));
});

// --- formatting -----------------------------------------------------------------

test("formatPesos", () => {
  assert.equal(formatPesos(0), "PHP 0.00");
  assert.equal(formatPesos(5), "PHP 0.05");
  assert.equal(formatPesos(123450), "PHP 1,234.50");
  assert.equal(formatPesos(123456789), "PHP 1,234,567.89");
  assert.equal(formatPesos(-150), "-PHP 1.50");
  assert.throws(() => formatPesos(1.5));
});

test("formatKm", () => {
  assert.equal(formatKm(0), "0 km");
  assert.equal(formatKm(999), "999 km");
  assert.equal(formatKm(1000), "1,000 km");
  assert.equal(formatKm(1234567), "1,234,567 km");
});

test("formatDate uses Asia/Manila", () => {
  assert.equal(formatDate("2026-11-15T03:00:00Z"), "15 Nov 2026");
  assert.equal(formatDate("2026-11-14T16:00:00Z"), "15 Nov 2026"); // 00:00 Manila
  assert.equal(formatDate("2026-11-14T15:59:59.999Z"), "14 Nov 2026");
  assert.equal(formatDateTime("2026-11-15T06:05:00Z"), "15 Nov 2026, 14:05 PHT");
  assert.equal(formatDateTime("2026-11-14T16:00:00Z"), "15 Nov 2026, 00:00 PHT");
  assert.equal(formatLocalDate("2025-03-01"), "1 Mar 2025");
});

test("formatInterval and formatLastDone", () => {
  assert.equal(formatInterval(60, 1500), "Every 1,500 km or 60 days");
  assert.equal(formatInterval(1, null), "Every 1 day");
  assert.equal(formatInterval(null, 5000), "Every 5,000 km");
  assert.equal(formatInterval(null, null), "-");
  assert.equal(formatLastDone("2026-11-01T02:00:00Z", 12000), "1 Nov 2026 at 12,000 km");
  assert.equal(formatLastDone("2026-11-01T02:00:00Z", null), "1 Nov 2026");
  assert.equal(formatLastDone(null, 12000), "At 12,000 km");
  assert.equal(formatLastDone(null, null), "-");
});

// --- sanitiser / wrapping ----------------------------------------------------------

test("sanitizeWinAnsi normalises typography and replaces unencodable characters", () => {
  assert.equal(sanitizeWinAnsi("“Oil” – 5W‑40 — it’s ok"), '"Oil" - 5W-40 - it\'s ok');
  assert.equal(sanitizeWinAnsi("₱1,500"), "PHP 1,500");
  assert.equal(sanitizeWinAnsi("Señor café €5 … •"), "Señor café €5 … •");
  assert.equal(sanitizeWinAnsi("café"), "café"); // NFC
  assert.equal(sanitizeWinAnsi("ok 🛵 中"), "ok ? ?");
  assert.equal(sanitizeWinAnsi("a\r\nb\rc\td\u0007e f"), "a\nb\nc d e f");
  assert.equal(sanitizeWinAnsi("x​y"), "xy");
});

test("wrapText wraps greedily, honours newlines and hard-breaks long words", () => {
  const measure = (s: string) => s.length; // one unit per character
  assert.deepEqual(wrapText("the quick brown fox", 9, measure), ["the quick", "brown fox"]);
  assert.deepEqual(wrapText("a  b", 9, measure), ["a b"]);
  assert.deepEqual(wrapText("one\n\ntwo", 9, measure), ["one", "", "two"]);
  assert.deepEqual(wrapText("ab ABCDEFGHIJKL cd", 5, measure), ["ab", "ABCDE", "FGHIJ", "KL cd"]);
  assert.deepEqual(wrapText("", 5, measure), [""]);
  for (const line of wrapText("lorem ipsum dolor sit amet consectetur", 7, measure)) assert.ok(line.length <= 7);
});

// --- verify / share -------------------------------------------------------------------

const HASH = "a".repeat(64);
const live = { revoked_at: null, share_token_hash: HASH, share_expires_at: "2026-11-22T00:00:00.000000+00" };

test("verifyStatus", () => {
  assert.equal(verifyStatus(null), "not_found");
  assert.equal(verifyStatus(undefined), "not_found");
  assert.equal(verifyStatus({ revoked_at: null }), "valid");
  assert.equal(verifyStatus({ revoked_at: "2026-11-16T00:00:00Z" }), "revoked");
});

test("shareAllowed: valid token before expiry", () => {
  assert.equal(shareAllowed(live, HASH, new Date("2026-11-21T23:59:59.999Z")), true);
});

test("shareAllowed: expiry boundary is exclusive", () => {
  assert.equal(shareAllowed(live, HASH, new Date("2026-11-22T00:00:00.000Z")), false);
  assert.equal(shareAllowed(live, HASH, new Date("2026-11-23T00:00:00.000Z")), false);
});

test("shareAllowed: wrong token, revoked, never shared, missing row", () => {
  const now = new Date("2026-11-16T00:00:00Z");
  assert.equal(shareAllowed(live, "b".repeat(64), now), false);
  assert.equal(shareAllowed(live, HASH.slice(1), now), false);
  assert.equal(shareAllowed(live, "", now), false);
  assert.equal(shareAllowed({ ...live, revoked_at: "2026-11-15T00:00:00Z" }, HASH, now), false);
  assert.equal(shareAllowed({ revoked_at: null, share_token_hash: null, share_expires_at: null }, "", now), false);
  assert.equal(shareAllowed(null, HASH, now), false);
  assert.equal(shareAllowed({ ...live, share_expires_at: "garbage" }, HASH, now), false);
});

test("verifyResponse exposes only public fields", () => {
  const snapshot = buildSnapshot(asset, [schedule({})], [log({ notes: "secret", shop: "Shop" })]);
  const r = verifyResponse({
    id: "r1",
    generated_at: "2026-11-15T06:05:00Z",
    revoked_at: null,
    content_hash: HASH,
    log_count: 1,
    snapshot,
  });
  assert.equal(r.status, "valid");
  assert.deepEqual(r.asset, { name: "Click 125", category: "vehicle", brand: "Honda", model: "Click 125i" });
  assert.deepEqual(r.service_logs, [{ performed_at: "2026-11-01T02:00:00.123Z", task: "Engine oil", odometer_km: 12000 }]);
  const json = JSON.stringify(r);
  for (const secret of ["secret", "Shop", "MH1JM1234", "45000"]) assert.equal(json.includes(secret), false, secret);
  assert.deepEqual(verifyResponse(null), { status: "not_found" });
});

test("verifyText headlines", () => {
  const snapshot = buildSnapshot(asset, [], [log({ schedule_id: null })]);
  const base = { id: "r1", generated_at: "2026-11-15T06:05:00Z", content_hash: HASH, log_count: 1, snapshot };
  const valid = verifyText(verifyResponse({ ...base, revoked_at: null }));
  assert.match(valid, /^VALID\n/);
  assert.match(valid, /Repair \/ other {2}12,000 km/);
  assert.match(valid, /Generated: {2}15 Nov 2026, 14:05 PHT/);
  const revoked = verifyText(verifyResponse({ ...base, revoked_at: "2026-11-16T01:00:00Z" }));
  assert.match(revoked, /^REVOKED\n/);
  assert.match(revoked, /revoked this report on 16 Nov 2026, 09:00 PHT/);
  assert.match(verifyText(verifyResponse(null)), /^NOT FOUND\n/);
});
