# Supabase backend

- `migrations/`: plain SQL, applied in filename order. Never edit one that's been applied; add a new file.
- `functions/_shared/engine.ts`: the server copy of the maintenance engine. It must pass `../engine-fixtures/` like the Kotlin engine.
- `functions/recompute-due/`: hourly job that recomputes due fields and sends FCM reminders.
- `functions/generate-report/` and `functions/verify-report/`: the verified maintenance PDF (see below).

## Tests

The shared modules use only erasable TypeScript and `node:` built-ins, so they run on Node 22.18+ or Deno:

```
node --test "supabase/functions/_shared/*.test.ts"
deno test --allow-read supabase/functions/_shared/
```

## recompute-due setup

1. Apply `migrations/20261115000000_server_engine.sql`. It enables `pg_cron` and `pg_net` and schedules the job at five past every hour.
2. Create the function secrets (Dashboard → Edge Functions → Secrets, or `supabase secrets set`):
   - `CRON_SECRET`: a long random string.
   - `FCM_SERVICE_ACCOUNT`: the Firebase service-account JSON (Project settings → Service accounts → Generate key). Optional; without it the job still recomputes but sends no pushes.
3. Store the values the cron job reads in Vault (SQL editor):
   ```sql
   select vault.create_secret('https://<project-ref>.supabase.co', 'kronokeep_project_url');
   select vault.create_secret('<same value as CRON_SECRET>', 'kronokeep_cron_secret');
   ```
4. Deploy: `supabase functions deploy recompute-due` (`config.toml` turns JWT verification off for it).

Manual run: `curl -X POST https://<project-ref>.supabase.co/functions/v1/recompute-due -H "x-cron-secret: <secret>"`. The response reports how many schedules were updated and what was delivered.

### Behaviour

- **Due fields** are written through `apply_schedule_due`, which keeps `updated_at` unchanged and skips a row a device edited since it was read. So a server recompute never beats a user's edit under last-write-wins, and devices (which compute due dates live from Room) don't re-pull rows every hour.
- **Reminders** go out between 08:00 and 21:00 Manila time as data-only FCM messages (`type=due`, `schedule_id`, `asset_id`, `asset_name`, `task`, `status`, `next_due_at`). Each `(schedule, status, cycle)` is claimed in `reminder_log` before sending, so overlapping runs can't send twice. A claim is released when no device could be reached (outage or only dead tokens), so it's retried. Dead tokens are deleted. The app dedupes pushes against the reminders it already showed locally.

## Verified reports

### Setup

1. Apply `migrations/20261116000000_reports.sql`. It adds `snapshot`, `log_count`, `share_token_hash` and `share_expires_at` to `reports`, and the RPCs `finalize_report` (service role only), `share_report` and `revoke_report` (signed-in owners). Clients can no longer UPDATE `reports` directly.
2. Deploy both functions:
   ```
   supabase functions deploy generate-report
   supabase functions deploy verify-report
   ```
   `generate-report` keeps JWT verification on. `verify-report` is public, so `config.toml` turns JWT verification off for it (on the dashboard: Edge Functions → verify-report → "Enforce JWT verification" off). No extra secrets: both use the runtime's `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`.

### Flow

1. The app calls `POST /functions/v1/generate-report` with `{"asset_id": "<uuid>"}` and the user's access token.
2. The function loads the asset, its schedules and its live service logs (service role, scoped to the caller). No live logs → 422 `no_service_logs`.
3. `_shared/report.ts` builds the snapshot: asset identity, every live service log (task name, date, km, cost, shop, notes) and the schedules. It contains only strings, integers and null, uses ISO UTC timestamps with millis and a fixed sort order, and is serialised as canonical JSON (sorted keys, no whitespace). `content_hash` is the SHA-256 of that exact text, which is stored in `reports.snapshot`; a CHECK constraint keeps the two consistent. The purchase price and predicted due dates are deliberately left out.
4. **Dedupe:** if a non-revoked report for the asset already has the same `content_hash`, it's returned with `"reused": true` and no new PDF is made. The report id and generation time aren't in the snapshot, so unchanged content always hashes the same. A partial unique index on `(asset_id, content_hash) where revoked_at is null` makes concurrent requests converge on one report.
5. Otherwise the PDF is rendered (`_shared/pdf.ts`: pdf-lib, a vector QR code to `verify-report?id=<id>`, the full hash on page 1 and a short hash on every page), uploaded to `reports/{user_id}/{asset_id}/{id}.pdf`, and `finalize_report` runs. In one transaction it locks the asset and its live logs, fails with `40001` (→ 409 `changed`) if the asset's `updated_at`, the set of live log ids, or any log's `updated_at` differs from what was read, then sets `locked_at` on the logs and inserts the `reports` row. On any failure after the upload, the object is removed.

### Lock semantics

- A log in a report gets `locked_at` and is read-only from then on: the `service_logs_locked_guard` trigger rejects every UPDATE (including soft deletes), and the app hides edit/delete for it. Locking bumps the logs' `updated_at`, so devices pull the lock on their next sync.
- Revoking a report does **not** unlock its logs: the PDF may already be in someone's hands, and the history it describes must not silently change.
- New logs can still be added; the next report includes them (new content → new hash → new report).

### Share links

- `share_report(report_id)` returns `{"token", "expires_at"}`. The token is 64 hex characters (two random UUIDs); only its SHA-256 is stored. It's valid for **7 days**, and calling `share_report` again rotates it, which kills the previous link.
- The link the app shares is `https://<project-ref>.supabase.co/functions/v1/verify-report?id=<report_id>&t=<token>`. `verify-report` checks the token (constant-time compare, not expired, report not revoked) and answers with a **302 to a 5-minute signed URL** for the PDF, or 410 otherwise. Nothing long-lived ever points at the storage object, so revoking takes effect immediately.
- `revoke_report(report_id)` is idempotent: it sets `revoked_at` (once) and clears the share token. Both RPCs raise `P0002` for a report the caller doesn't own and `share_report` raises `22023` for a revoked report.

### Verifying

`GET /functions/v1/verify-report?id=<report_id>` answers VALID / REVOKED / NOT FOUND as plain text, or as JSON with `?format=json` or `Accept: application/json`:

```
curl "https://<project-ref>.supabase.co/functions/v1/verify-report?id=<report_id>&format=json"
```

The JSON includes the asset name/category/brand/model, the entry count and each entry's date, task and km from the stored snapshot, so a buyer can compare it with the printed PDF. Shop, notes, costs and the serial number are never exposed. The function re-hashes the stored snapshot first; a row that doesn't match its hash is reported as not found and logged. Responses are `Cache-Control: no-store`.

### Tests

The snapshot, canonical JSON, hashing, formatting, WinAnsi sanitiser, word wrap and verify/share rules live in `_shared/report.ts` and are covered by `report.test.ts` (part of `node --test "supabase/functions/_shared/*.test.ts"`). `pdf.ts` and the two functions use `npm:` imports and are checked with `deno check supabase/functions/generate-report/index.ts supabase/functions/verify-report/index.ts`.
