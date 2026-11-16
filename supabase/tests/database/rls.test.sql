-- RLS isolation tests (pgTAP). Run with `supabase test db` against a local stack; CI does this on every push.
-- Never run against the hosted project. Everything happens inside one transaction that is rolled back.
--
-- Cast: user A owns one asset with a child row in every table, two storage objects, a report, a reminder
-- and a device token. User B owns nothing of A's and tries to read, write, re-parent and delete it all.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;

select plan(43);

-- ---------------------------------------------------------------------------
-- Fixtures (as postgres, bypassing RLS)
-- ---------------------------------------------------------------------------
insert into auth.users (id, email) values
  ('aaaaaaaa-0000-4000-8000-000000000001', 'a@rls.test'),
  ('bbbbbbbb-0000-4000-8000-000000000002', 'b@rls.test');

insert into public.assets (id, user_id, category, name) values
  ('a1a1a1a1-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'vehicle', 'A scooter'),
  ('b1b1b1b1-0000-4000-8000-000000000002', 'bbbbbbbb-0000-4000-8000-000000000002', 'appliance', 'B aircon');

insert into public.maintenance_schedules (id, user_id, asset_id, task, interval_days) values
  ('a2a2a2a2-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001', 'Oil', 60);

insert into public.service_logs (id, user_id, asset_id, performed_at, locked_at) values
  ('a3a3a3a3-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001', now(), null),
  ('a3a3a3a3-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001', now(), now());

insert into public.odometer_readings (id, user_id, asset_id, km, recorded_at) values
  ('a4a4a4a4-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001', 1000, now());

insert into public.documents (id, user_id, asset_id, kind, storage_path, mime_type, size_bytes, sha256) values
  ('a5a5a5a5-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001',
   'receipt', 'aaaaaaaa-0000-4000-8000-000000000001/a1a1a1a1-0000-4000-8000-000000000001/a5.jpg', 'image/jpeg', 10, repeat('0', 64));

insert into public.reports (id, user_id, asset_id, storage_path, content_hash, snapshot, log_count) values
  ('a6a6a6a6-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001',
   'aaaaaaaa-0000-4000-8000-000000000001/a1a1a1a1-0000-4000-8000-000000000001/a6.pdf',
   encode(sha256(convert_to('{}', 'UTF8')), 'hex'), '{}', 1);

insert into public.reminder_log (schedule_id, status, cycle_key, user_id) values
  ('a2a2a2a2-0000-4000-8000-000000000001', 'overdue', 'c1', 'aaaaaaaa-0000-4000-8000-000000000001');

insert into public.device_tokens (token, user_id) values ('token-a', 'aaaaaaaa-0000-4000-8000-000000000001');

insert into storage.objects (bucket_id, name) values
  ('documents', 'aaaaaaaa-0000-4000-8000-000000000001/a1a1a1a1-0000-4000-8000-000000000001/a5.jpg'),
  ('reports', 'aaaaaaaa-0000-4000-8000-000000000001/a1a1a1a1-0000-4000-8000-000000000001/a6.pdf');

-- ---------------------------------------------------------------------------
-- Guard: no table in public may exist without RLS
-- ---------------------------------------------------------------------------
select is_empty(
  $$ select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r', 'p') and not c.relrowsecurity $$,
  'every public table has RLS enabled'
);

-- ---------------------------------------------------------------------------
-- User B: can't see A's rows
-- ---------------------------------------------------------------------------
set local role authenticated;
set local "request.jwt.claims" to '{"sub":"bbbbbbbb-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from public.assets), 1, 'B sees only their own asset');
select is((select count(*)::int from public.assets where user_id <> auth.uid()), 0, 'B sees no asset of A');
select is_empty($$ select 1 from public.maintenance_schedules $$, 'B sees no schedules of A');
select is_empty($$ select 1 from public.service_logs $$, 'B sees no service logs of A');
select is_empty($$ select 1 from public.odometer_readings $$, 'B sees no odometer readings of A');
select is_empty($$ select 1 from public.documents $$, 'B sees no documents of A');
select is_empty($$ select 1 from public.reports $$, 'B sees no reports of A');
select is_empty($$ select 1 from public.reminder_log $$, 'B sees no reminder log of A');
select is_empty($$ select 1 from public.device_tokens $$, 'B sees no device tokens of A');
select is_empty($$ select 1 from storage.objects where bucket_id = 'documents' $$, 'B sees no files in A''s documents folder');
select is_empty($$ select 1 from storage.objects where bucket_id = 'reports' $$, 'B sees no report PDFs of A');

-- ---------------------------------------------------------------------------
-- User B: can't change A's rows (RLS filters them out, so 0 rows are touched)
-- ---------------------------------------------------------------------------
select results_eq(
  $$ with u as (update public.assets set name = 'pwned' where id = 'a1a1a1a1-0000-4000-8000-000000000001' returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t update A''s asset');
select results_eq(
  $$ with u as (update public.assets set deleted_at = now() where id = 'a1a1a1a1-0000-4000-8000-000000000001' returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t soft-delete A''s asset');
select results_eq(
  $$ with u as (update public.maintenance_schedules set interval_days = 1 returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t update A''s schedules');
select results_eq(
  $$ with u as (update public.service_logs set notes = 'x' where locked_at is null returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t update A''s service logs');
select results_eq(
  $$ with u as (update public.odometer_readings set km = 0 returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t update A''s odometer readings');
select results_eq(
  $$ with u as (update public.documents set kind = 'other' returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t update A''s documents');
select results_eq(
  $$ with d as (delete from public.device_tokens where token = 'token-a' returning 1) select count(*)::int from d $$,
  $$ values (0) $$, 'B can''t delete A''s device token');
select results_eq(
  $$ with u as (update storage.objects set name = name || '.x' where bucket_id = 'documents' returning 1) select count(*)::int from u $$,
  $$ values (0) $$, 'B can''t rename A''s files');

-- ---------------------------------------------------------------------------
-- User B: can't create rows in A's name or under A's asset
-- ---------------------------------------------------------------------------
select throws_ok(
  $$ insert into public.assets (id, user_id, category, name)
     values ('b9b9b9b9-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'vehicle', 'planted') $$,
  '42501', null, 'B can''t insert an asset owned by A');
select throws_ok(
  $$ insert into public.maintenance_schedules (id, user_id, asset_id, task, interval_days)
     values ('b9b9b9b9-0000-4000-8000-000000000002', 'bbbbbbbb-0000-4000-8000-000000000002', 'a1a1a1a1-0000-4000-8000-000000000001', 'x', 1) $$,
  '42501', null, 'B can''t attach a schedule to A''s asset');
select throws_ok(
  $$ insert into public.service_logs (id, user_id, asset_id, performed_at)
     values ('b9b9b9b9-0000-4000-8000-000000000003', 'bbbbbbbb-0000-4000-8000-000000000002', 'a1a1a1a1-0000-4000-8000-000000000001', now()) $$,
  '42501', null, 'B can''t attach a service log to A''s asset');
select throws_ok(
  $$ insert into public.odometer_readings (id, user_id, asset_id, km, recorded_at)
     values ('b9b9b9b9-0000-4000-8000-000000000004', 'bbbbbbbb-0000-4000-8000-000000000002', 'a1a1a1a1-0000-4000-8000-000000000001', 1, now()) $$,
  '42501', null, 'B can''t attach an odometer reading to A''s asset');
select throws_ok(
  $$ insert into public.documents (id, user_id, asset_id, kind, storage_path, mime_type, size_bytes, sha256)
     values ('b9b9b9b9-0000-4000-8000-000000000005', 'bbbbbbbb-0000-4000-8000-000000000002', 'a1a1a1a1-0000-4000-8000-000000000001', 'other',
             'bbbbbbbb-0000-4000-8000-000000000002/a1a1a1a1-0000-4000-8000-000000000001/x.pdf', 'application/pdf', 1, repeat('0', 64)) $$,
  '42501', null, 'B can''t attach a document to A''s asset');
select throws_ok(
  $$ insert into public.device_tokens (token, user_id) values ('token-b', 'aaaaaaaa-0000-4000-8000-000000000001') $$,
  '42501', null, 'B can''t register a token for A');
select throws_ok(
  $$ insert into storage.objects (bucket_id, name)
     values ('documents', 'aaaaaaaa-0000-4000-8000-000000000001/a1a1a1a1-0000-4000-8000-000000000001/planted.jpg') $$,
  '42501', null, 'B can''t upload into A''s documents folder');
select lives_ok(
  $$ insert into storage.objects (bucket_id, name)
     values ('documents', 'bbbbbbbb-0000-4000-8000-000000000002/b1b1b1b1-0000-4000-8000-000000000002/mine.jpg') $$,
  'B can upload into their own folder');
select throws_ok(
  $$ insert into storage.objects (bucket_id, name)
     values ('reports', 'bbbbbbbb-0000-4000-8000-000000000002/b1b1b1b1-0000-4000-8000-000000000002/fake.pdf') $$,
  '42501', null, 'B can''t upload a report PDF, even into their own folder');

-- Re-parenting: B can't hand their own row to A (WITH CHECK on update).
select throws_ok(
  $$ update public.assets set user_id = 'aaaaaaaa-0000-4000-8000-000000000001' where id = 'b1b1b1b1-0000-4000-8000-000000000002' $$,
  '42501', null, 'B can''t give their asset to A');

-- ---------------------------------------------------------------------------
-- User B: server-only functions and other people's reports
-- ---------------------------------------------------------------------------
select throws_ok($$ select public.share_report('a6a6a6a6-0000-4000-8000-000000000001') $$, 'P0002', null, 'B can''t share A''s report');
select throws_ok($$ select public.revoke_report('a6a6a6a6-0000-4000-8000-000000000001') $$, 'P0002', null, 'B can''t revoke A''s report');
select throws_ok($$ select public.finalize_report('{}'::jsonb) $$, '42501', null, 'finalize_report is service-role only');
select throws_ok($$ select public.apply_schedule_due('[]'::jsonb) $$, '42501', null, 'apply_schedule_due is service-role only');

-- ---------------------------------------------------------------------------
-- User A: owner rules still hold for the owner
-- ---------------------------------------------------------------------------
set local "request.jwt.claims" to '{"sub":"aaaaaaaa-0000-4000-8000-000000000001","role":"authenticated"}';

select is((select name from public.assets where id = 'a1a1a1a1-0000-4000-8000-000000000001'), 'A scooter', 'A sees their asset, unchanged by B');
select is((select count(*)::int from public.service_logs), 2, 'A sees both of their service logs');
select results_eq(
  $$ with d as (delete from public.assets where id = 'a1a1a1a1-0000-4000-8000-000000000001' returning 1) select count(*)::int from d $$,
  $$ values (0) $$, 'A can''t hard-delete a synced row (soft delete only)');
select throws_ok(
  $$ update public.service_logs set notes = 'edited' where id = 'a3a3a3a3-0000-4000-8000-000000000002' $$,
  '23514', null, 'A can''t edit a log locked by a verified report');
select throws_ok(
  $$ insert into public.service_logs (id, user_id, asset_id, performed_at, locked_at)
     values ('a3a3a3a3-0000-4000-8000-000000000003', 'aaaaaaaa-0000-4000-8000-000000000001', 'a1a1a1a1-0000-4000-8000-000000000001', now(), now()) $$,
  '42501', null, 'A can''t insert a pre-locked log');
select throws_ok(
  $$ update public.reports set revoked_at = now() $$,
  '42501', null, 'A can''t update reports directly (RPCs only)');
select throws_ok(
  $$ insert into public.reminder_log (schedule_id, status, cycle_key, user_id)
     values ('a2a2a2a2-0000-4000-8000-000000000001', 'due_soon', 'c2', 'aaaaaaaa-0000-4000-8000-000000000001') $$,
  '42501', null, 'A can''t write the reminder log');

-- ---------------------------------------------------------------------------
-- Anonymous: no table access at all
-- ---------------------------------------------------------------------------
reset role;
set local role anon;
set local "request.jwt.claims" to '{"role":"anon"}';
select throws_ok($$ select 1 from public.assets $$, '42501', null, 'anon can''t read assets');
select throws_ok($$ select 1 from public.reports $$, '42501', null, 'anon can''t read reports');

select * from finish();
rollback;
