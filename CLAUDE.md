# CLAUDE.md

This file guides Claude Code when working in this repository.

## What this app is

**KronoKeep** is a native Android app (package `com.kronokeep.app`) that serves as a vault for assets, warranties and preventive maintenance. Users log their vehicles, home appliances and electronics once, attach receipts and serial numbers, and set usage baselines. The app predicts when maintenance is due, keeps the repair history, and exports a **verified maintenance PDF** to hand over when the asset is sold.

It is a **portfolio piece** meant to impress employers, not a store release. Polish, clean architecture, tests and a good README matter more than breadth. The technical highlights to protect are:
1. **Predictive maintenance engine.** It combines the calendar trigger with the usage trigger, and the usage rate is an EWMA (exponentially weighted moving average) of odometer readings.
2. **Secure document storage.** Files sit in a private bucket and are reached only through short-lived signed URLs.
3. **Offline-first sync**, with Room as the local source of truth.
4. **Server-side verified PDF**, carrying a content hash and a QR code that links to a verify endpoint.

## How to work here

- **Just build it.** Keep explanations minimal and favour progress. Explain only design decisions that aren't obvious, and keep that to one or two lines.
- **Dependencies:** anything in the agreed stack below may be added freely. Ask before adding a library that isn't on that list.
- **Done means verified:** run `assembleDebug` and the unit tests before reporting a task as finished. If either fails, say so and include the output.
- **Tests are required for logic:** the engine, sync/outbox, repositories, mappers and anything in `domain/` need JVM unit tests. UI gets Compose tests only for key flows (add asset, log service).
- **Database:** schema changes go **only** through new files in `supabase/migrations/`. Never run DDL or ad-hoc writes against the hosted Supabase project, and never edit a migration that has already been applied; add a new one instead.
- **Secrets:** the Supabase URL and publishable key live in `local.properties` (gitignored) and reach code through `BuildConfig`. Service-role keys never go into the app or the repo; they belong only in Edge Function secrets.

## Git

- One branch per milestone or feature: `feat/m<N>-<slug>`, `fix/<slug>`, `chore/<slug>`. `main` stays buildable.
- Commit messages follow **Conventional Commits**: `feat(engine): …`, `fix(sync): …`, `chore(build): …`, `test(engine): …`, `docs: …`. Scopes: `engine`, `sync`, `storage`, `report`, `auth`, `ui`, `db`, `build`, `supabase`.
- **Commit only when the user asks.** Never push, force-push or rewrite history without being asked.
- Never add Claude as a co-author in commits or PRs.

## Commands (Windows / PowerShell, run from the repo root)

The `java` on PATH is Java 8, which is too old for Gradle 9, so point Gradle at Android Studio's bundled JDK first. When Claude Code runs Gradle, the daemon fails with `Unable to establish loopback connection` unless `JAVA_TOOL_OPTIONS` moves the socket temp directory, and the command has to run outside the sandbox:

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:/Users/ASUSTU~1/.gradle/udstmp"
.\gradlew.bat :app:assembleDebug                 # build debug APK
.\gradlew.bat :app:testDebugUnitTest             # JVM unit tests
.\gradlew.bat :app:testDebugUnitTest --tests "com.kronokeep.app.domain.engine.*"
.\gradlew.bat :app:connectedDebugAndroidTest     # instrumented / Compose UI tests
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:installDebug
```

The Supabase CLI isn't installed yet. Migrations are plain SQL in `supabase/migrations/` and the user applies them.

## Build setup

- **AGP 9.5 with built-in Kotlin.** Do NOT apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`. The root file declares it `apply false` only to pin the Kotlin Gradle plugin version, which must match `kotlin.plugin.compose` and `kotlin.plugin.serialization` (all use `kotlin` in `gradle/libs.versions.toml`).
- compileSdk/targetSdk 37, minSdk 26, Java 17. All versions live in `gradle/libs.versions.toml`.
- KSP handles both Hilt and Room. Room exports schemas to `app/schemas/` (commit them; they drive migrations).
- `local.properties` keys: `supabase.url`, `supabase.publishableKey`. When they're missing, the app builds and runs offline and shows a "Cloud not configured" banner.

## Agreed stack

Kotlin · Jetpack Compose + Material 3 · Navigation 3 · Hilt · Room · DataStore · WorkManager · kotlinx.serialization · Coil 3 · supabase-kt (auth, postgrest, storage, functions) on Ktor OkHttp · CameraX · ML Kit Text Recognition + Barcode Scanning · Firebase Cloud Messaging (FCM). Backend: Supabase (Postgres + RLS, Auth, Storage, Edge Functions in TypeScript/Deno, `pg_cron`); `pdf-lib` in the report function.

## Architecture

MVVM with unidirectional data flow, organised as clean-architecture packages inside the single `:app` module:

- `domain/`: **pure Kotlin, with no Android or Supabase imports.** It holds models, repository interfaces and use cases.
  - `domain/engine/`: the maintenance prediction engine. It must stay pure and deterministic, taking `now` as a parameter and never reading the clock itself.
- `data/local/`: Room entities, DAOs, `KronoDatabase`, and the outbox.
- `data/remote/`: supabase-kt DTOs and data sources.
- `data/repository/`: repository implementations. They **always read from Room**, and every write goes to Room plus an `outbox` row in **one transaction**.
- `data/sync/`: `SyncWorker` (push the outbox, then pull changes since the last sync).
- `di/`: Hilt modules.
- `navigation/`: `Routes.kt` (`@Serializable` `NavKey`s), the back stack and the `Navigator`.
- `ui/<feature>/`: screens plus ViewModels, which expose `StateFlow<UiState>`. `ui/theme/` holds the design tokens; `ui/components/` holds shared composables.

To add a screen, add a route in `navigation/Routes.kt`, then add an `entry<...>` in `ui/KronoKeepAppRoot.kt`.

### Data model (Postgres is canonical; Room mirrors it)

`assets`, `maintenance_schedules`, `service_logs`, `odometer_readings`, `documents`, `reports`, `device_tokens`. The SQL lives in `supabase/migrations/`.

- IDs are **UUIDs generated on the client**, so offline-created rows never need remapping.
- Every synced table carries `user_id`, `created_at`, `updated_at` (set by a server trigger) and `deleted_at` (soft delete). Hard deletes never happen on synced tables.
- Money is stored as `Long` centavos (`price_centavos`) and distances as `Int` km. Time is UTC `Instant`; plain dates are `LocalDate`.
- `service_logs.locked_at`: once a log is included in a report it is read-only. The UI and the DB trigger must both enforce this.

### Maintenance engine rules

- Due point = **whichever comes first** of the calendar trigger (`last_done_at + interval_days`) and the usage trigger (`(last_done_km + interval_km − current_km) / km_per_day`).
- `km_per_day` is an EWMA over `odometer_readings`. With fewer than 2 readings, it falls back to the user's weekly baseline ÷ 7.
- The engine outputs `nextDueAt`, `nextDueKm`, `status` (`OK` / `DUE_SOON` / `OVERDUE`) and a `confidence` from 0 to 1. The UI shows a ± range and never fakes precision.
- `DUE_SOON` applies when ≤14 days or ≤10% of the interval remains.
- The engine exists twice, as Kotlin (`domain/engine`) and TypeScript (`supabase/functions/_shared/engine.ts`). **Both must pass the same fixtures** in `engine-fixtures/*.json`. Change both in the same branch, or neither.

### Storage and security rules

- The `documents` bucket is **private**. Paths follow `{user_id}/{asset_id}/{uuid}.{ext}`, and RLS on `storage.objects` checks that the first path segment is `auth.uid()`.
- Uploads use signed upload URLs. Viewing uses signed download URLs that expire after **≤10 minutes** and are never cached past that. Never call `getPublicUrl`.
- Before uploading, compress images on the device (longest side 2048 px, JPEG quality 85) and record the SHA-256 in `documents.sha256`. Allowed types are JPEG, PNG, HEIC and PDF, up to 15 MB.
- Every table has RLS enabled with `user_id = auth.uid()` policies. A new table without RLS is a bug.

### Sync rules

- Writes go to Room plus an outbox entry. `SyncWorker` runs when the device is connected, with exponential backoff.
- Conflicts resolve last-write-wins on `updated_at` per row. Pulls page by `updated_at > lastSyncedAt`.
- Rows carry `syncState` (`PENDING` / `SYNCED` / `FAILED`) so the UI can show "Saved on phone" vs "Synced".

## Product decisions (agreed with the user)

- Asset categories: **vehicle**, **appliance**, **electronic**.
- Locale: **Philippines**. Units are km, money is PHP (₱), and dates are formatted for `en-PH`. Units aren't configurable in v1.
- Presets (editable, in `domain/presets/`): a scooter (Click / Mio / NMAX / Aerox) needs oil every ~1,500 km or 2 months, gear oil every ~5,000 km, CVT cleaning every ~5,000 km and the air filter every ~10,000 km. A car needs PMS (routine maintenance service) every 5,000 km or 6 months. A split-type aircon needs cleaning every 3–6 months. A water filter needs replacing every 6 months. Electronics track the warranty only.
- First-version extras: receipt OCR (ML Kit, on-device), barcode/serial scan, odometer update prompts. **Household sharing is deferred to v2.**
- The PDF is generated **server-side** (Edge Function `generate-report`). Public `verify-report?id=` returns Valid / Revoked / Not found. Share links expire after 7 days.

## Phase tracker

**Keep this section up to date. It is the single record of project progress.**
- When a task inside a phase lands, tick its box (`[x]`).
- When a phase is finished (build and tests pass), move it to **Completed**. Add the date, the branch, the final commit, and a short note of what was actually built. Then promote the next phase to **Current**.
- Work that gets deferred or cut moves to **Backlog** with a one-line reason. It is never silently dropped.
- Update this tracker in the same change as the work it describes.

Status key: ✅ done · 🔨 in progress · ⏳ not started

### Completed

**Phase 1: Foundation ✅** (2026-11-09, `feat/m1-foundation`, commit `e7bf2e1`; not yet merged to `main`)
- [x] Gradle setup: AGP 9.5 with built-in Kotlin, version catalog, KSP for Hilt and Room
- [x] Hilt DI (`di/AppModule.kt`): app scope, injected `Clock`, database and DAOs, repository bindings
- [x] Room schema v1, mirroring Postgres: assets, schedules, service logs, odometer readings, documents, outbox. Exported to `app/schemas/`.
- [x] Supabase client via `SupabaseProvider`. It's null when `local.properties` has no keys, and the app then runs offline with a banner.
- [x] Email auth: `AuthRepository`, plus `AuthScreen` and `AuthViewModel`
- [x] App shell: vault theme (teal and brass, status colours), Navigation 3 with one back stack per tab, Dashboard / Assets / Settings placeholders
- [x] `Peso` value class (centavos), with formatting and parsing tests
- [x] Migration `20261109000000_init.sql`: tables, RLS (including `owns_asset` checks on child tables), locked-log trigger, `reports` writable only on `revoked_at`, private `documents` and `reports` buckets with owner-folder policies
- [x] Unit tests: `PesoTest`, `ConvertersTest`, `AuthValidationTest` (10 passing)

**Phase 2: Assets and documents ✅** (2026-11-09, `feat/m2-assets-documents`, commit `e12213d`; not yet merged)
- [x] Domain: create, update, archive and soft-delete asset use cases, with validation (`domain/asset/`, limits mirror the SQL CHECKs)
- [x] Repository writes: Room plus an outbox row in one transaction, with `syncState = PENDING` (`TransactionRunner` seam; outbox coalesces to one entry per row)
- [x] Add-asset wizard: category → details (brand, model, serial, purchase date, price in ₱, warranty) → baselines (odometer, weekly km). Non-vehicles skip baselines; the first odometer value seeds an `odometer_readings` row.
- [x] Asset detail screen with tabs: Schedule · History · Documents (Schedule and History are placeholders until Phase 3)
- [x] Edit, archive and delete asset (category is fixed after creation; archived list toggle on the Assets tab)
- [x] Documents: system camera (FileProvider, no CAMERA permission), photo picker, document picker; EXIF-rotated, ≤2048 px, JPEG 85, SHA-256, `files/documents/{docId}.ext`
- [x] Upload queue (WorkManager, custom `WorkerFactory` without hilt-work): signed upload URL → `{user}/{asset}/{doc}.{ext}` → `storage_path`, then the local copy is deleted. Unit-tested with fakes; **not yet exercised against a live Supabase project**.
- [x] Document viewer: local copy first, otherwise a 10-minute signed URL fetched into memory (Coil memory and disk caches off; remote PDFs go to a temp file deleted on close)
- [x] Tests: 59 new JVM tests (69 total) plus a 3-test Compose `AddAssetFlowTest`, passing on an API 37 emulator

**Phase 3: Maintenance engine ✅** (2026-11-09, `feat/m3-maintenance-engine` from `feat/m2-assets-documents`, commits `10052cb`…`08cd09a`; not yet merged)

Agreed decisions: time-decay EWMA (per-interval km/day weighted by a 30-day half-life; baseline only below 2 readings; confidence from reading count, recency and rate variance). Presets show up as a "Suggested schedules" wizard step and as "Add from preset" / "Custom" on the Schedule tab. Aircon default is 3 months. One service visit can tick several tasks (one `service_logs` row per task, cost on the first) or be an ad-hoc repair. New schedules default "last done" to today and the current odometer. Odometer nudges: dashboard card and detail highlight after 7 days, a notification after 14. Reminders: daily WorkManager job around 08:00 local, one notification per status change; `POST_NOTIFICATIONS` is requested when the first schedule is created; Settings has an on/off toggle.
- [x] `domain/engine`: `MaintenanceEngine` (pure, `now` passed in, maths in epoch-ms doubles rounded to whole seconds so TS can match exactly). Readings under 1 day apart are skipped; a lower reading restarts the series; usage projects from the latest reading, so due dates don't drift day to day without new data. A reading already past the due km counts as observed overdue (confidence 1). Output includes `uncertaintyDays` (± window) and `projectedKm`.
- [x] `engine-fixtures/`: 10 JSON cases plus a README (format, tolerances). Expected values were worked out by hand or computed separately, not copied from engine output. `EngineFixturesTest` runs them all.
- [x] `domain/presets` (scooter, car, aircon, water filter; whole-word name/brand/model hints pick a default). Schedule add/edit/delete, multi-task service visits, delete log (blocked when `lockedAt` is set), odometer update (can't go backwards). `ScheduleRecomputer` re-runs the engine inside each write transaction and persists due fields with an outbox row (confidence only when it moves by 0.05 or more, to avoid sync churn). Room schema is unchanged (still v1), and there's no new migration.
- [x] UI: wizard "Suggested schedules" step (vehicles: step 4 of 4, appliances: step 3 of 3, electronics skip it); Schedule tab (odometer card with rate and confidence, status pills, "Due in N days ± M d", preset sheet, custom editor); History tab (task names survive schedule deletion, lock icon, total spent); Log service screen; odometer dialog.
- [x] Dashboard: headline (overdue / due soon / all clear), overdue, due soon, next 3 upcoming, stale odometers, warranties (60 days ahead, 30 days after expiry). Predictions are computed live from Room, not read from stored fields.
- [x] Daily `ReminderWorker` (KEEP periodic, initial delay to 08:00). `ReminderRunner` recomputes, plans with the pure `ReminderPlanner`, posts one digest notification, and saves a DataStore ledger only after posting, so blocked notifications aren't lost. Recompute also runs on app start.
- [x] Tests: engine, fixtures, validator, presets, use cases, repository (transactions, rollback, back-dated logs, lock, revert on delete), recompute, planner, dashboard, settings and ledger. Compose: `AddAssetFlowTest` (4 tests, now including the schedules step) and the new `LogServiceFlowTest` (2 tests), passing on an API 37 emulator.

**Phase 4: Sync ✅** (2026-11-10, `feat/m4-sync` from `feat/m3-maintenance-engine`; not yet merged)
- [x] `SyncEngine` (`data/sync/`) runs claim → push → pull. Pushes go parents first, 100 rows per batch. Pulls take pages of 500 by keyset (`updated_at`, `id`), because one `now()` can stamp many rows. Cursors are stored in DataStore per account and table. `SyncWorker` uses KEEP and re-runs up to 3 times inside the worker when writes land mid-run. `SyncTrigger` fires on sign-in and 2 s after outbox writes settle. There's also a 6-hour periodic pull, and "Sync now" (REPLACE) in Settings.
- [x] Last-write-wins: before pushing, the engine reads the server copies. A server row newer than the local edit (compared at millis) wins, and a log locked by a report always wins. A row turns SYNCED only if its outbox `seq` hasn't changed, so edits made mid-push survive. On pull, pending local edits that are newer are kept. Soft deletes arrive as ordinary rows. Pulled readings and schedules trigger a recompute.
- [x] Errors: a 4xx (other than 401, 408 or 429) is a rejection. The engine retries that batch row by row and keeps `attempts` and `lastError`; after 5 failures the row becomes FAILED until it's edited again. Everything else is transient, and WorkManager retries it with backoff. A child row whose parent isn't local yet stops its table's cursor, and another pass picks it up.
- [x] Offline rows (`userId = null`) are claimed at the start of every sync, and claiming a document kicks `UploadScheduler`. Documents wait in the outbox until `storagePath` is set; one deleted or lost before upload is dropped from the outbox. Once a document's soft delete has been pushed, its storage object is deleted (best effort).
- [x] UI: Settings has a Sync row (status line, spinner, "Sync now"). The asset and document badges were already in place. No Room schema change and no new migration.
- [x] Tests: 31 new JVM tests (175 total), covering `SyncEngineTest` (16), `SyncTableTest` (7), `SyncRepositoryImplTest` (3) and `SyncStatusTextTest` (5). A `FakeSyncRemote` models server-owned `updated_at` with microseconds, RLS on `user_id`, rejections and outages. **Not yet exercised against a live Supabase project.**

**Phase 5: Server engine ✅** (2026-11-15, `feat/m5-server-engine` from `feat/m4-sync`; committed and pushed, not yet merged)
- [x] `supabase/functions/_shared/engine.ts`: a line-for-line port of the Kotlin engine in erasable TypeScript (it runs on Deno and on Node's type stripping). It passes all 10 fixtures (`engine.test.ts`, using only `node:` built-ins). Run with `node --test "supabase/functions/_shared/*.test.ts"`, which gives 30 tests.
- [x] `recompute-due` Edge Function: the pure `_shared/recompute.ts` plans due-field updates (same 0.05 confidence step as `ScheduleRecomputer`) and reminder candidates (archived assets get no reminders). Writes go through the `apply_schedule_due` RPC, which keeps `updated_at` and skips a row edited since it was read, so the server never wins LWW over a user and doesn't trigger re-pulls. Auth is an `x-cron-secret` header (`verify_jwt = false` in `supabase/config.toml`). `pg_cron` runs it at :05 every hour via `pg_net`, with the URL and secret in Vault. Setup steps are in `supabase/README.md`.
- [x] FCM server: `_shared/fcm.ts` handles HTTP v1 with a WebCrypto-signed service-account JWT (no dependency; the secret `FCM_SERVICE_ACCOUNT`). Messages are data-only, high priority, with a 1-day TTL. They're sent only between 08:00 and 21:00 Manila time. `reminder_log` (RLS, owner read-only) is claimed with `on conflict do nothing` before each send, keyed by (schedule, status, cycle), where cycle = last done at/km plus intervals. A claim is released when no device got it. Dead tokens are deleted.
- [x] Migration `20261115000000_server_engine.sql`: `set_updated_at` honours a service-role-only preserve flag. It also adds `apply_schedule_due`, `register_device_token` (reassigns a token between accounts) and `unregister_device_token`, the `reminder_log` table, and the cron job.
- [x] Android FCM: Firebase starts manually from the `local.properties` keys `firebase.projectId`, `firebase.applicationId`, `firebase.apiKey` and `firebase.gcmSenderId`. There's no google-services plugin and no init provider, and without the keys the app runs as before. `PushTokenRegistrar` registers on sign-in and on token rotation, and skips the call when nothing changed. `SignOutUseCase` unregisters first. `KronoMessagingService` decides with the pure `PushReminderPolicy`, which shares the device `ReminderLedger` (under a shared lock), so local and push reminders notify once per status change.
- [x] Notification actions and deep links: tapping opens Asset detail, or the Dashboard for a multi-asset digest. "Log service" opens with the task preselected. "Mark done" (`ReminderActionReceiver` → `MarkScheduleDoneUseCase`) logs at the current km, and on failure shows a notification that opens Log service. Cold start and `onNewIntent` both work, and a pending link survives sign-in.
- [x] Tests: 35 new JVM tests (210 total), covering the push parser, policy, handler, token registrar, deep links and mark-done, plus 30 Node tests on the server side. **Not yet exercised against live Supabase/Firebase or on a device** (pushes, notification actions).
- [x] Deployed 2026-11-15 to Supabase project `kronokeep` (ref `djiaejvceiiahbffqnbr`, ap-southeast-1), at the user's request via the Supabase MCP. Applied migrations: `init` (fixed before its first apply anywhere, because `owns_asset` was created before `assets`), `server_engine`, and `20261115000100_advisor_fixes` (moves `pg_net` to `extensions`, revokes `owns_asset` from anon). `recompute-due` v1 is deployed with `verify_jwt=false`. The cron job and both Vault entries exist (the cron secret was generated in the database). The user has set the `CRON_SECRET` Edge secret. **Firebase is not configured yet** (as of 2026-11-15 there's no Firebase project): `FCM_SERVICE_ACCOUNT` is unset and the `firebase.*` keys in `local.properties` are blank. Until then, push reminders are off and the job only recomputes (it reports `fcm not configured`); on-device daily reminders still work. Later phases don't depend on it. The remaining advisor warnings (`owns_asset` and `register_device_token` callable by `authenticated`) are intended.

**Phase 6: Verified PDF ✅** (2026-11-16, `feat/m6-verified-pdf` from `feat/m5-server-engine`; not yet committed)

Agreed decision: QR codes via `npm:qrcode` (drawn as vector squares with pdf-lib; `@types/qrcode` is types-only, for `deno check`).
- [x] Migration `20261116000000_reports.sql`: `reports` gains `snapshot` (the exact hashed canonical JSON; a CHECK ties `content_hash` to it), `log_count`, `share_token_hash`, `share_expires_at`. Clients can no longer UPDATE reports. A partial unique index on (asset, hash) where not revoked makes concurrent generates produce one report. `finalize_report` (service role) locks the asset and its live logs, raises 40001 if anything moved since it was read, sets `locked_at` and inserts the row in one transaction. `share_report` / `revoke_report` RPCs (owner only).
- [x] `generate-report`: snapshot (asset, live logs with task names, schedules; no price or predictions, no report id) → canonical JSON → SHA-256. Identical content returns the existing live report (`reused`). Otherwise renders an A4 PDF (`_shared/pdf.ts`: asset block, paginated history, schedules, QR verification box, hash footer), uploads to `reports/{user}/{asset}/{id}.pdf`, then finalizes; any failure removes the upload. Errors: 401 / 404 / 422 `no_service_logs` / 409 `changed` / 500.
- [x] Public `verify-report?id=` (`verify_jwt = false`): Valid / Revoked / Not found, as plain text (Supabase serves Edge Function HTML as text/plain) or JSON (`format=json`). It re-checks the snapshot hash and lists entry dates, tasks and km so a buyer can compare them with the printout. Share links are `verify-report?id=&t=<token>`: 7 days, revocable, rotated on re-share, and they 302 to a 5-minute signed URL. The token is never stored, only its hash.
- [x] App: `ReportRepository` (server-only; reports aren't mirrored in Room, and Room schema is unchanged). `GenerateReportUseCase` refuses while the asset has outbox rows or isn't synced (and kicks a sync), so the report never misses local edits; after a new report it requests a sync to pull the locks. Reports screen (from the asset's overflow menu: "Verified report") with generate, status pills, short hash, share-sheet link, revoke with confirm. In-app PDF viewer through a 10-minute signed URL and a temp file deleted on close (reuses `PdfPages`).
- [x] Tests: 21 new JVM tests (231 total): `ReportRepositoryImplTest` (8), `GenerateReportUseCaseTest` (7), `ReportFormatsTest` (6). 22 new Node tests (52 total) for canonical JSON, hashing, snapshot, formatting, WinAnsi sanitising, wrapping and verify/share rules. The PDF was rendered and inspected once outside the repo. **Not deployed, and not yet exercised against a live Supabase project.** Deno isn't installed, so `deno check` hasn't run.

### Current

**Phase 7: Smart capture ⏳** (next up)
- [ ] Receipt OCR (ML Kit) to fill in the date, store, price and serial number
- [ ] Barcode/serial scan (CameraX + ML Kit)

**Phase 8: Polish and portfolio 🔨** (started early; design system committed on `feat/m2-assets-documents` as `17642be`)
- [x] Design system: direction C "Asset Tag" chosen from three previews (`design/previews/`). Theme.kt has light + dark schemes, status container colours, bundled Schibsted Grotesk + IBM Plex Mono (OFL, `assets/licenses/`), shapes. Settings has a System / Light / Dark toggle (DataStore `SettingsRepository`); on API 31+ it's mirrored to `UiModeManager` so the launch window matches.
- [ ] Restyle asset cards as asset tags and draw due windows (needs the Phase 3 engine output)
- [ ] GitHub Actions CI: lint, unit tests, `assembleDebug`
- [ ] RLS tests in SQL (one user can't read or write another's rows or files)
- [ ] README with architecture diagram and screenshots, demo video, seeded demo account

### Backlog (v2 / deferred)
- Household sharing (shared vaults). Deferred because it changes every RLS policy.
- Configurable units and currency (km/mi, non-PHP).
- Editing a service log in place (Phase 3 ships delete plus re-log). Deferred because a multi-task visit is several rows, and editing one needs a "visit" grouping the schema doesn't have yet.
- Separating local data per account on sign-out. Deferred because rows left by one account stay visible (though never pushed) if a different account signs in on the same phone, and wiping on sign-out would destroy unsynced work.
- Base-version conflict detection, meaning each row stores the server `updated_at` it was edited from. Deferred because v1 LWW compares the phone's clock with server time, so a badly skewed clock resolves conflicts only approximately. It needs a Room schema v2.

**Current state:** Phases 1–5 are done, committed and pushed (`feat/m1-foundation`, `feat/m2-assets-documents`, `feat/m3-maintenance-engine`, `feat/m4-sync`, `feat/m5-server-engine`; none merged to `main`). Phase 5 is deployed to Supabase project `djiaejvceiiahbffqnbr`; **Firebase/FCM isn't configured yet**, so push reminders stay off until it is. Phase 6 (verified PDF) is built and tested on `feat/m6-verified-pdf` but **not committed or deployed**: the migration `20261116000000_reports.sql` and the `generate-report` and `verify-report` functions still need to be applied. Phase 7 (smart capture) is next.
