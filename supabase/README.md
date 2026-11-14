# Supabase backend

- `migrations/`: plain SQL, applied in filename order. Never edit one that's been applied; add a new file.
- `functions/_shared/engine.ts`: the server copy of the maintenance engine. It must pass `../engine-fixtures/` like the Kotlin engine.
- `functions/recompute-due/`: hourly job that recomputes due fields and sends FCM reminders.

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
