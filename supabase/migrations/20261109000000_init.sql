-- KronoKeep: initial schema, RLS and storage policies.
-- Postgres is canonical; app/src/main/java/com/kronokeep/app/data/local/entity/Entities.kt mirrors it.
-- Rules: every table has RLS; synced tables soft-delete (deleted_at) and have no DELETE policy;
-- ids are client-generated UUIDs; money is centavos (bigint); distances are km (integer).

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------
create type public.asset_category as enum ('vehicle', 'appliance', 'electronic');
create type public.maintenance_status as enum ('ok', 'due_soon', 'overdue');
create type public.document_kind as enum ('receipt', 'warranty', 'manual', 'photo', 'other');

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------
-- Server owns updated_at, so pulls can page by `updated_at > last_synced_at` without clock skew.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- True when the asset exists and belongs to the caller. Used by child-table policies so a user
-- can't attach rows to someone else's asset by guessing its UUID.
create or replace function public.owns_asset(p_asset_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.assets a
    where a.id = p_asset_id and a.user_id = (select auth.uid())
  );
$$;

-- ---------------------------------------------------------------------------
-- assets
-- ---------------------------------------------------------------------------
create table public.assets (
  id                  uuid primary key,
  user_id             uuid not null default auth.uid() references auth.users (id) on delete cascade,
  category            public.asset_category not null,
  name                text not null check (char_length(name) between 1 and 120),
  brand               text check (char_length(brand) <= 80),
  model               text check (char_length(model) <= 80),
  serial_no           text check (char_length(serial_no) <= 120),
  purchase_date       date,
  price_centavos      bigint check (price_centavos >= 0),
  warranty_expires_on date,
  current_odometer_km integer check (current_odometer_km >= 0),
  weekly_km_baseline  integer check (weekly_km_baseline >= 0),
  notes               text check (char_length(notes) <= 2000),
  archived_at         timestamptz,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  deleted_at          timestamptz
);
create index assets_user_updated_idx on public.assets (user_id, updated_at);

-- ---------------------------------------------------------------------------
-- maintenance_schedules
-- ---------------------------------------------------------------------------
create table public.maintenance_schedules (
  id            uuid primary key,
  user_id       uuid not null default auth.uid() references auth.users (id) on delete cascade,
  asset_id      uuid not null references public.assets (id) on delete cascade,
  task          text not null check (char_length(task) between 1 and 120),
  interval_days integer check (interval_days > 0),
  interval_km   integer check (interval_km > 0),
  last_done_at  timestamptz,
  last_done_km  integer check (last_done_km >= 0),
  -- Computed by the engine (device + recompute-due Edge Function).
  next_due_at   timestamptz,
  next_due_km   integer,
  confidence    real check (confidence between 0 and 1),
  status        public.maintenance_status not null default 'ok',
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  constraint schedule_has_interval check (interval_days is not null or interval_km is not null)
);
create index schedules_user_updated_idx on public.maintenance_schedules (user_id, updated_at);
create index schedules_asset_idx on public.maintenance_schedules (asset_id);
create index schedules_due_idx on public.maintenance_schedules (next_due_at) where deleted_at is null;

-- ---------------------------------------------------------------------------
-- service_logs
-- ---------------------------------------------------------------------------
create table public.service_logs (
  id            uuid primary key,
  user_id       uuid not null default auth.uid() references auth.users (id) on delete cascade,
  asset_id      uuid not null references public.assets (id) on delete cascade,
  schedule_id   uuid references public.maintenance_schedules (id) on delete set null,
  performed_at  timestamptz not null,
  odometer_km   integer check (odometer_km >= 0),
  cost_centavos bigint check (cost_centavos >= 0),
  shop          text check (char_length(shop) <= 120),
  notes         text check (char_length(notes) <= 2000),
  -- Set by generate-report (service role). A locked log is part of a verified report: immutable.
  locked_at     timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz
);
create index service_logs_user_updated_idx on public.service_logs (user_id, updated_at);
create index service_logs_asset_idx on public.service_logs (asset_id, performed_at);

create or replace function public.prevent_locked_log_changes()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.locked_at is not null then
    raise exception 'service log % is locked by a verified report', old.id
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;

-- UPDATE only: clients have no DELETE policy, and hard deletes (account deletion cascade) must still work.
create trigger service_logs_locked_guard
  before update on public.service_logs
  for each row execute function public.prevent_locked_log_changes();

-- ---------------------------------------------------------------------------
-- odometer_readings
-- ---------------------------------------------------------------------------
create table public.odometer_readings (
  id          uuid primary key,
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  asset_id    uuid not null references public.assets (id) on delete cascade,
  km          integer not null check (km >= 0),
  recorded_at timestamptz not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz
);
create index odometer_user_updated_idx on public.odometer_readings (user_id, updated_at);
create index odometer_asset_idx on public.odometer_readings (asset_id, recorded_at);

-- ---------------------------------------------------------------------------
-- documents (metadata; bytes live in the private `documents` bucket)
-- ---------------------------------------------------------------------------
create table public.documents (
  id             uuid primary key,
  user_id        uuid not null default auth.uid() references auth.users (id) on delete cascade,
  asset_id       uuid not null references public.assets (id) on delete cascade,
  service_log_id uuid references public.service_logs (id) on delete set null,
  kind           public.document_kind not null,
  storage_path   text not null,
  mime_type      text not null check (mime_type in ('image/jpeg', 'image/png', 'image/heic', 'application/pdf')),
  size_bytes     bigint not null check (size_bytes between 1 and 15728640),
  sha256         text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz,
  -- Path must be {user_id}/{asset_id}/{file}; storage RLS relies on the first segment.
  constraint documents_path_matches_owner check (
    storage_path like user_id::text || '/' || asset_id::text || '/%'
  )
);
create index documents_user_updated_idx on public.documents (user_id, updated_at);
create index documents_asset_idx on public.documents (asset_id);

-- ---------------------------------------------------------------------------
-- reports (written only by the generate-report Edge Function with the service role)
-- ---------------------------------------------------------------------------
create table public.reports (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users (id) on delete cascade,
  asset_id     uuid not null references public.assets (id) on delete cascade,
  storage_path text not null,
  content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
  generated_at timestamptz not null default now(),
  revoked_at   timestamptz
);
create index reports_asset_idx on public.reports (asset_id);

-- ---------------------------------------------------------------------------
-- device_tokens (FCM)
-- ---------------------------------------------------------------------------
create table public.device_tokens (
  token      text primary key,
  user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
  platform   text not null default 'android' check (platform in ('android')),
  updated_at timestamptz not null default now()
);
create index device_tokens_user_idx on public.device_tokens (user_id);

-- ---------------------------------------------------------------------------
-- updated_at triggers
-- ---------------------------------------------------------------------------
create trigger assets_updated_at before update on public.assets
  for each row execute function public.set_updated_at();
create trigger schedules_updated_at before update on public.maintenance_schedules
  for each row execute function public.set_updated_at();
create trigger service_logs_updated_at before update on public.service_logs
  for each row execute function public.set_updated_at();
create trigger odometer_updated_at before update on public.odometer_readings
  for each row execute function public.set_updated_at();
create trigger documents_updated_at before update on public.documents
  for each row execute function public.set_updated_at();
create trigger device_tokens_updated_at before update on public.device_tokens
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------------
alter table public.assets                enable row level security;
alter table public.maintenance_schedules enable row level security;
alter table public.service_logs          enable row level security;
alter table public.odometer_readings     enable row level security;
alter table public.documents             enable row level security;
alter table public.reports               enable row level security;
alter table public.device_tokens         enable row level security;

-- assets: owner only. No DELETE policy: clients soft-delete via deleted_at.
create policy "assets: owner select" on public.assets
  for select to authenticated using (user_id = (select auth.uid()));
create policy "assets: owner insert" on public.assets
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy "assets: owner update" on public.assets
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

-- Child tables: owner only, and the parent asset must also be the caller's.
create policy "schedules: owner select" on public.maintenance_schedules
  for select to authenticated using (user_id = (select auth.uid()));
create policy "schedules: owner insert" on public.maintenance_schedules
  for insert to authenticated
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));
create policy "schedules: owner update" on public.maintenance_schedules
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));

create policy "service_logs: owner select" on public.service_logs
  for select to authenticated using (user_id = (select auth.uid()));
create policy "service_logs: owner insert" on public.service_logs
  for insert to authenticated
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id) and locked_at is null);
create policy "service_logs: owner update" on public.service_logs
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));

create policy "odometer: owner select" on public.odometer_readings
  for select to authenticated using (user_id = (select auth.uid()));
create policy "odometer: owner insert" on public.odometer_readings
  for insert to authenticated
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));
create policy "odometer: owner update" on public.odometer_readings
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));

create policy "documents: owner select" on public.documents
  for select to authenticated using (user_id = (select auth.uid()));
create policy "documents: owner insert" on public.documents
  for insert to authenticated
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));
create policy "documents: owner update" on public.documents
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and public.owns_asset(asset_id));

-- reports: owners read, and may only flip revoked_at. Inserts come from the service role.
create policy "reports: owner select" on public.reports
  for select to authenticated using (user_id = (select auth.uid()));
create policy "reports: owner revoke" on public.reports
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));
revoke update on public.reports from authenticated;
grant update (revoked_at) on public.reports to authenticated;

-- device_tokens: owner manages their own tokens (a device re-registering after sign-out is an upsert).
create policy "device_tokens: owner select" on public.device_tokens
  for select to authenticated using (user_id = (select auth.uid()));
create policy "device_tokens: owner insert" on public.device_tokens
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy "device_tokens: owner update" on public.device_tokens
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));
create policy "device_tokens: owner delete" on public.device_tokens
  for delete to authenticated using (user_id = (select auth.uid()));

-- Nothing here is public. Anonymous callers get no table access at all.
revoke all on all tables in schema public from anon;

-- ---------------------------------------------------------------------------
-- Storage: private buckets, owner-folder policies
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('documents', 'documents', false, 15728640,
     array['image/jpeg', 'image/png', 'image/heic', 'application/pdf']),
  ('reports', 'reports', false, 20971520, array['application/pdf'])
on conflict (id) do nothing;

-- documents bucket: object names are {user_id}/{asset_id}/{uuid}.{ext}.
create policy "documents bucket: owner read" on storage.objects
  for select to authenticated
  using (bucket_id = 'documents' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "documents bucket: owner upload" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'documents' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "documents bucket: owner update" on storage.objects
  for update to authenticated
  using (bucket_id = 'documents' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'documents' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "documents bucket: owner delete" on storage.objects
  for delete to authenticated
  using (bucket_id = 'documents' and (storage.foldername(name))[1] = (select auth.uid())::text);

-- reports bucket: written by the service role only; owners may read (signed URLs are minted server-side).
create policy "reports bucket: owner read" on storage.objects
  for select to authenticated
  using (bucket_id = 'reports' and (storage.foldername(name))[1] = (select auth.uid())::text);
