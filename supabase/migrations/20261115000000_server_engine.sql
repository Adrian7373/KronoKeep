-- KronoKeep Phase 5: server engine.
-- * recompute-due writes due fields without bumping updated_at (so it never wins LWW over a user's edit,
--   and devices, which compute due dates live, don't re-pull every hourly tick).
-- * Device token registration RPCs (a token can move between accounts on one phone).
-- * reminder_log: each push reminder is sent once per (schedule, status, service cycle).
-- * pg_cron runs recompute-due hourly via pg_net. Needs two Vault secrets (see supabase/README.md):
--   kronokeep_project_url, kronokeep_cron_secret.

-- ---------------------------------------------------------------------------
-- updated_at: let trusted server maintenance preserve it
-- ---------------------------------------------------------------------------
-- Only honoured for the service role, so a client can't freeze updated_at and dodge LWW or pulls.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if current_user in ('service_role', 'postgres')
     and coalesce(current_setting('kronokeep.preserve_updated_at', true), '') = 'on' then
    new.updated_at := old.updated_at;
  else
    new.updated_at := now();
  end if;
  return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- apply_schedule_due: batch write of engine output (service role only)
-- ---------------------------------------------------------------------------
-- p_updates: [{id, seen_updated_at, next_due_at, next_due_km, confidence, status}, ...]
-- A row edited since it was read (updated_at moved) is skipped; the device already recomputed it.
create or replace function public.apply_schedule_due(p_updates jsonb)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  n integer;
begin
  perform set_config('kronokeep.preserve_updated_at', 'on', true);
  update public.maintenance_schedules s
     set next_due_at = u.next_due_at,
         next_due_km = u.next_due_km,
         confidence  = u.confidence,
         status      = u.status
    from jsonb_to_recordset(p_updates) as u(
           id uuid,
           seen_updated_at timestamptz,
           next_due_at timestamptz,
           next_due_km integer,
           confidence real,
           status public.maintenance_status)
   where s.id = u.id
     and s.updated_at = u.seen_updated_at
     and s.deleted_at is null;
  get diagnostics n = row_count;
  perform set_config('kronokeep.preserve_updated_at', 'off', true);
  return n;
end;
$$;

revoke execute on function public.apply_schedule_due(jsonb) from public, anon, authenticated;
grant execute on function public.apply_schedule_due(jsonb) to service_role;

-- ---------------------------------------------------------------------------
-- Device tokens
-- ---------------------------------------------------------------------------
-- An FCM token belongs to an app install, not an account. If another account signed in on this phone
-- before, the row is theirs and the owner-only UPDATE policy would block the upsert; this hands it over.
create or replace function public.register_device_token(p_token text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := (select auth.uid());
begin
  if uid is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;
  if p_token is null or char_length(p_token) not between 1 and 4096 then
    raise exception 'invalid device token' using errcode = '22023';
  end if;
  insert into public.device_tokens (token, user_id, platform)
  values (p_token, uid, 'android')
  on conflict (token) do update
    set user_id = excluded.user_id,
        platform = excluded.platform,
        updated_at = now();
end;
$$;

-- Invoker rights: the existing owner-delete policy already limits it to the caller's own token.
create or replace function public.unregister_device_token(p_token text)
returns void
language sql
set search_path = ''
as $$
  delete from public.device_tokens where token = p_token and user_id = (select auth.uid());
$$;

revoke execute on function public.register_device_token(text) from public, anon;
revoke execute on function public.unregister_device_token(text) from public, anon;
grant execute on function public.register_device_token(text) to authenticated;
grant execute on function public.unregister_device_token(text) to authenticated;

-- ---------------------------------------------------------------------------
-- reminder_log (written by recompute-due with the service role)
-- ---------------------------------------------------------------------------
-- One row per reminder sent. cycle_key = last_done_at|last_done_km|interval_days|interval_km, so logging a
-- service (or changing the interval) starts a new cycle that notifies again. The row is inserted *before*
-- sending (insert … on conflict do nothing), which makes overlapping runs send at most once.
create table public.reminder_log (
  schedule_id uuid not null references public.maintenance_schedules (id) on delete cascade,
  status      public.maintenance_status not null check (status in ('due_soon', 'overdue')),
  cycle_key   text not null check (char_length(cycle_key) <= 200),
  user_id     uuid not null references auth.users (id) on delete cascade,
  sent_at     timestamptz not null default now(),
  primary key (schedule_id, status, cycle_key)
);
create index reminder_log_user_idx on public.reminder_log (user_id);

alter table public.reminder_log enable row level security;

-- Owners may read their own history; only the service role writes.
create policy "reminder_log: owner select" on public.reminder_log
  for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.reminder_log from anon;
revoke insert, update, delete on public.reminder_log from authenticated;

-- ---------------------------------------------------------------------------
-- Hourly schedule
-- ---------------------------------------------------------------------------
create extension if not exists pg_cron;
create extension if not exists pg_net;

-- Five past the hour. The function itself decides whether it's inside the 08:00–21:00 Manila send window.
-- cron.schedule with an existing job name replaces it, so re-running this is harmless.
select cron.schedule(
  'kronokeep-recompute-due',
  '5 * * * *',
  $cron$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name = 'kronokeep_project_url')
           || '/functions/v1/recompute-due',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-cron-secret', (select decrypted_secret from vault.decrypted_secrets where name = 'kronokeep_cron_secret')
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 60000
  );
  $cron$
);
