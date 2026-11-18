-- Account deletion (pgTAP). Run with `supabase test db` against a local stack; never against the hosted project.
-- Deleting an auth user (what delete-account does after purging storage) must remove every row the user owns,
-- locked service logs included, and leave other users untouched. Rolled back at the end.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;

select plan(13);

insert into auth.users (id, email) values
  ('cccccccc-0000-4000-8000-000000000003', 'c@delete.test'),
  ('dddddddd-0000-4000-8000-000000000004', 'd@delete.test');

insert into public.assets (id, user_id, category, name) values
  ('c1c1c1c1-0000-4000-8000-000000000003', 'cccccccc-0000-4000-8000-000000000003', 'vehicle', 'C scooter'),
  ('d1d1d1d1-0000-4000-8000-000000000004', 'dddddddd-0000-4000-8000-000000000004', 'vehicle', 'D scooter');

insert into public.maintenance_schedules (id, user_id, asset_id, task, interval_days) values
  ('c2c2c2c2-0000-4000-8000-000000000003', 'cccccccc-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003', 'Oil', 60),
  ('d2d2d2d2-0000-4000-8000-000000000004', 'dddddddd-0000-4000-8000-000000000004', 'd1d1d1d1-0000-4000-8000-000000000004', 'Oil', 60);

-- C's logs point at the schedule, one of them locked by a report: the SET NULL cascade must not trip the guard.
insert into public.service_logs (id, user_id, asset_id, schedule_id, performed_at, locked_at) values
  ('c3c3c3c3-0000-4000-8000-000000000001', 'cccccccc-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003',
   'c2c2c2c2-0000-4000-8000-000000000003', now(), now()),
  ('c3c3c3c3-0000-4000-8000-000000000002', 'cccccccc-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003',
   'c2c2c2c2-0000-4000-8000-000000000003', now(), null),
  ('d3d3d3d3-0000-4000-8000-000000000004', 'dddddddd-0000-4000-8000-000000000004', 'd1d1d1d1-0000-4000-8000-000000000004',
   'd2d2d2d2-0000-4000-8000-000000000004', now(), now());

insert into public.odometer_readings (id, user_id, asset_id, km, recorded_at) values
  ('c4c4c4c4-0000-4000-8000-000000000003', 'cccccccc-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003', 1000, now());

insert into public.documents (id, user_id, asset_id, service_log_id, kind, storage_path, mime_type, size_bytes, sha256) values
  ('c5c5c5c5-0000-4000-8000-000000000003', 'cccccccc-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003',
   'c3c3c3c3-0000-4000-8000-000000000001', 'receipt',
   'cccccccc-0000-4000-8000-000000000003/c1c1c1c1-0000-4000-8000-000000000003/c5.jpg', 'image/jpeg', 10, repeat('0', 64));

insert into public.reports (id, user_id, asset_id, storage_path, content_hash, snapshot, log_count) values
  ('c6c6c6c6-0000-4000-8000-000000000003', 'cccccccc-0000-4000-8000-000000000003', 'c1c1c1c1-0000-4000-8000-000000000003',
   'cccccccc-0000-4000-8000-000000000003/c1c1c1c1-0000-4000-8000-000000000003/c6.pdf',
   encode(sha256(convert_to('{}', 'UTF8')), 'hex'), '{}', 1);

insert into public.reminder_log (schedule_id, status, cycle_key, user_id) values
  ('c2c2c2c2-0000-4000-8000-000000000003', 'overdue', 'c1', 'cccccccc-0000-4000-8000-000000000003');

insert into public.device_tokens (token, user_id) values ('token-c', 'cccccccc-0000-4000-8000-000000000003');

-- The guard still holds for everything else: the owner can't null the schedule of a locked log while it exists.
set local role authenticated;
set local "request.jwt.claims" to '{"sub":"dddddddd-0000-4000-8000-000000000004","role":"authenticated"}';
select throws_ok(
  $$ update public.service_logs set schedule_id = null where id = 'd3d3d3d3-0000-4000-8000-000000000004' $$,
  '23514', null, 'a locked log''s schedule can''t be cleared while the schedule exists'
);
reset role;

select lives_ok(
  $$ delete from auth.users where id = 'cccccccc-0000-4000-8000-000000000003' $$,
  'deleting a user with a locked, scheduled service log succeeds'
);

select is((select count(*)::int from public.assets where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'assets gone');
select is((select count(*)::int from public.maintenance_schedules where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'schedules gone');
select is((select count(*)::int from public.service_logs where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'service logs gone, locked one included');
select is((select count(*)::int from public.odometer_readings where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'odometer readings gone');
select is((select count(*)::int from public.documents where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'documents gone');
select is((select count(*)::int from public.reports where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'reports gone, so verify answers NOT FOUND');
select is((select count(*)::int from public.reminder_log where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'reminder log gone');
select is((select count(*)::int from public.device_tokens where user_id = 'cccccccc-0000-4000-8000-000000000003'), 0, 'device tokens gone');

select is((select count(*)::int from public.assets where user_id = 'dddddddd-0000-4000-8000-000000000004'), 1, 'other user''s asset untouched');
select is(
  (select schedule_id from public.service_logs where id = 'd3d3d3d3-0000-4000-8000-000000000004'),
  'd2d2d2d2-0000-4000-8000-000000000004'::uuid,
  'other user''s locked log untouched'
);
select is((select count(*)::int from auth.users where id = 'dddddddd-0000-4000-8000-000000000004'), 1, 'other user still exists');

select * from finish();
rollback;
