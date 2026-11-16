-- KronoKeep Phase 6: verified reports.
-- * reports stores the exact canonical JSON that was hashed (snapshot), so verify-report can re-check the
--   hash and show a buyer what the PDF should contain.
-- * finalize_report (service role, called by generate-report) re-checks that nothing changed since the
--   snapshot was read, locks the logs and inserts the row in one transaction.
-- * Share links: a random token whose SHA-256 is stored (never the token). share_report rotates it,
--   revoke_report kills it. Clients can no longer UPDATE reports directly.

-- ---------------------------------------------------------------------------
-- reports: new columns
-- ---------------------------------------------------------------------------
-- NOT NULL without a default is safe: no report has been generated yet, so the table is empty everywhere.
alter table public.reports
  add column snapshot         text not null,
  add column log_count        integer not null check (log_count >= 0),
  add column share_token_hash text check (share_token_hash ~ '^[0-9a-f]{64}$'),
  add column share_expires_at timestamptz;

alter table public.reports
  add constraint reports_share_pair check ((share_token_hash is null) = (share_expires_at is null)),
  add constraint reports_hash_matches_snapshot check (
    content_hash = encode(sha256(convert_to(snapshot, 'UTF8')), 'hex')
  );

-- generate-report reuses a live report with the same content; this also makes two concurrent requests for
-- the same content produce one report (the loser gets 23505 and returns the winner's row).
create unique index reports_live_content_uidx on public.reports (asset_id, content_hash)
  where revoked_at is null;

-- ---------------------------------------------------------------------------
-- reports: clients read only; revoke/share go through the RPCs below
-- ---------------------------------------------------------------------------
drop policy if exists "reports: owner revoke" on public.reports;
revoke update (revoked_at) on public.reports from authenticated;
revoke insert, update, delete, truncate on public.reports from authenticated;
revoke all on public.reports from anon;
-- "reports: owner select" stays.

-- ---------------------------------------------------------------------------
-- finalize_report (service role only)
-- ---------------------------------------------------------------------------
-- p = {id, user_id, asset_id, storage_path, content_hash, snapshot, log_count, generated_at,
--      asset_seen_updated_at, logs: [{id, seen_updated_at}]}
-- Timestamps arrive as the raw PostgREST strings (microseconds) and are compared as timestamptz.
-- Raises 40001 'report source changed' when the asset or its live logs moved since they were read.
create or replace function public.finalize_report(p jsonb)
returns void
language plpgsql
set search_path = ''
as $$
declare
  v_asset_id     uuid        := (p ->> 'asset_id')::uuid;
  v_user_id      uuid        := (p ->> 'user_id')::uuid;
  v_generated_at timestamptz := (p ->> 'generated_at')::timestamptz;
  v_logs         jsonb       := coalesce(p -> 'logs', '[]'::jsonb);
  v_asset_seen   timestamptz := (p ->> 'asset_seen_updated_at')::timestamptz;
  v_asset_now    timestamptz;
  v_mismatches   integer;
begin
  if jsonb_typeof(v_logs) <> 'array' then
    raise exception 'logs must be an array' using errcode = '22023';
  end if;
  if (p ->> 'log_count')::integer is distinct from jsonb_array_length(v_logs) then
    raise exception 'log_count does not match logs' using errcode = '22023';
  end if;

  -- Locking the asset also blocks new service_logs for it (their FK check takes KEY SHARE on the asset).
  select a.updated_at into v_asset_now
    from public.assets a
   where a.id = v_asset_id and a.user_id = v_user_id and a.deleted_at is null
     for update;
  if not found or v_asset_now <> v_asset_seen then
    raise exception 'report source changed' using errcode = '40001';
  end if;

  perform 1
     from public.service_logs l
    where l.asset_id = v_asset_id and l.deleted_at is null
    order by l.id
      for update;

  -- Any live log not in p.logs, any p.logs entry that isn't a live log, or any updated_at that moved.
  select count(*) into v_mismatches
    from (select l.id, l.updated_at
            from public.service_logs l
           where l.asset_id = v_asset_id and l.deleted_at is null) live
    full join (select x.id, x.seen_updated_at
                 from jsonb_to_recordset(v_logs) as x(id uuid, seen_updated_at timestamptz)) seen
      on seen.id = live.id
   where live.id is null
      or seen.id is null
      or live.updated_at is distinct from seen.seen_updated_at;
  if v_mismatches > 0 then
    raise exception 'report source changed' using errcode = '40001';
  end if;

  -- Already-locked logs are skipped: the locked-log trigger rejects any UPDATE on them. set_updated_at
  -- bumps updated_at on the rest, which is intended (devices pull the lock).
  update public.service_logs l
     set locked_at = v_generated_at
   where l.asset_id = v_asset_id
     and l.deleted_at is null
     and l.locked_at is null;

  insert into public.reports (id, user_id, asset_id, storage_path, content_hash, generated_at, snapshot, log_count)
  values (
    (p ->> 'id')::uuid,
    v_user_id,
    v_asset_id,
    p ->> 'storage_path',
    p ->> 'content_hash',
    v_generated_at,
    p ->> 'snapshot',
    (p ->> 'log_count')::integer
  );
end;
$$;

revoke execute on function public.finalize_report(jsonb) from public, anon, authenticated;
grant execute on function public.finalize_report(jsonb) to service_role;

-- ---------------------------------------------------------------------------
-- share_report / revoke_report (owners)
-- ---------------------------------------------------------------------------
-- Returns {"token": text, "expires_at": timestamptz}. Re-sharing rotates the token, killing the old link.
-- The token is 64 hex chars from two v4 UUIDs (244 random bits); only its SHA-256 is stored.
create or replace function public.share_report(p_report_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid     uuid := (select auth.uid());
  v_revoked timestamptz;
  v_token   text;
  v_expires timestamptz := now() + interval '7 days';
begin
  select r.revoked_at into v_revoked
    from public.reports r
   where r.id = p_report_id and r.user_id = v_uid
     for update;
  if not found then
    raise exception 'report not found' using errcode = 'P0002';
  end if;
  if v_revoked is not null then
    raise exception 'report revoked' using errcode = '22023';
  end if;

  v_token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  update public.reports
     set share_token_hash = encode(sha256(convert_to(v_token, 'UTF8')), 'hex'),
         share_expires_at = v_expires
   where id = p_report_id;

  return jsonb_build_object('token', v_token, 'expires_at', v_expires);
end;
$$;

-- Idempotent. The report's logs stay locked: the PDF may already be in a buyer's hands.
create or replace function public.revoke_report(p_report_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  update public.reports
     set revoked_at       = coalesce(revoked_at, now()),
         share_token_hash = null,
         share_expires_at = null
   where id = p_report_id and user_id = v_uid;
  if not found then
    raise exception 'report not found' using errcode = 'P0002';
  end if;
end;
$$;

revoke execute on function public.share_report(uuid) from public, anon;
revoke execute on function public.revoke_report(uuid) from public, anon;
grant execute on function public.share_report(uuid) to authenticated;
grant execute on function public.revoke_report(uuid) to authenticated;
