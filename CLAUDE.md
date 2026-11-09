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

### Current

**Phase 2: Assets and documents 🔨** (branch `feat/m2-assets-documents`; all work done, build and tests pass, **not committed yet**)
- [x] Domain: create, update, archive and soft-delete asset use cases, with validation (`domain/asset/`, limits mirror the SQL CHECKs)
- [x] Repository writes: Room plus an outbox row in one transaction, with `syncState = PENDING` (`TransactionRunner` seam; outbox coalesces to one entry per row)
- [x] Add-asset wizard: category → details (brand, model, serial, purchase date, price in ₱, warranty) → baselines (odometer, weekly km). Non-vehicles skip baselines; the first odometer value seeds an `odometer_readings` row.
- [x] Asset detail screen with tabs: Schedule · History · Documents (Schedule and History are placeholders until Phase 3)
- [x] Edit, archive and delete asset (category is fixed after creation; archived list toggle on the Assets tab)
- [x] Documents: system camera (FileProvider, no CAMERA permission), photo picker, document picker; EXIF-rotated, ≤2048 px, JPEG 85, SHA-256, `files/documents/{docId}.ext`
- [x] Upload queue (WorkManager, custom `WorkerFactory` without hilt-work): signed upload URL → `{user}/{asset}/{doc}.{ext}` → `storage_path`, then the local copy is deleted. Unit-tested with fakes; **not yet exercised against a live Supabase project**.
- [x] Document viewer: local copy first, otherwise a 10-minute signed URL fetched into memory (Coil memory and disk caches off; remote PDFs go to a temp file deleted on close)
- [x] Tests: 59 new JVM tests (69 total) plus a 3-test Compose `AddAssetFlowTest`, passing on an API 37 emulator

### Future

**Phase 3: Maintenance engine ⏳**
- [ ] `domain/engine`: calendar and usage triggers, EWMA km/day, confidence, status bands
- [ ] `engine-fixtures/*.json`, shared with the TypeScript engine in Phase 5
- [ ] `domain/presets` (PH scooter, car, aircon, water filter), schedule CRUD, log service, odometer quick update
- [ ] Dashboard: overdue, due soon, warranties expiring
- [ ] Daily WorkManager job for local notifications (works offline)

**Phase 4: Sync ⏳**
- [ ] `SyncWorker`: push the outbox, then pull by `updated_at > lastSyncedAt` (paged)
- [ ] Last-write-wins conflict handling, soft-delete propagation, `syncState` in the UI
- [ ] Claim offline rows (`userId = null`) on first sign-in, then kick `UploadScheduler` (the uploader only takes rows owned by the signed-in user)
- [ ] Hold back `documents` outbox entries until `storagePath` is set (the server column is NOT NULL)
- [ ] Optional: delete orphaned storage objects for soft-deleted documents

**Phase 5: Server engine ⏳**
- [ ] `supabase/functions/_shared/engine.ts`, passing the same fixtures as the Kotlin engine
- [ ] `recompute-due` Edge Function, run hourly by `pg_cron`
- [ ] FCM: device token registration, a reminder log so each reminder is sent once, notification actions

**Phase 6: Verified PDF ⏳**
- [ ] `generate-report`: canonical JSON → SHA-256 → lock logs → `pdf-lib` PDF with QR code → `reports` row
- [ ] Public `verify-report?id=` (Valid / Revoked / Not found)
- [ ] Export and share screen: 7-day signed link, revoke

**Phase 7: Smart capture ⏳**
- [ ] Receipt OCR (ML Kit) to fill in the date, store, price and serial number
- [ ] Barcode/serial scan (CameraX + ML Kit)

**Phase 8: Polish and portfolio ⏳**
- [ ] GitHub Actions CI: lint, unit tests, `assembleDebug`
- [ ] RLS tests in SQL (one user can't read or write another's rows or files)
- [ ] README with architecture diagram and screenshots, demo video, seeded demo account

### Backlog (v2 / deferred)
- Household sharing (shared vaults). Deferred because it changes every RLS policy.
- Configurable units and currency (km/mi, non-PHP).

**Current state:** Phase 1 is done and committed on `feat/m1-foundation` (not merged). Phase 2 is implemented on `feat/m2-assets-documents` (branched from `feat/m1-foundation`): build, unit tests and Compose tests pass, and it is waiting to be committed. Once it is, move it to Completed with the commit hash.
