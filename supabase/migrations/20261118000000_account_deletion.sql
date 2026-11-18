-- Account deletion (delete-account Edge Function → auth.admin.deleteUser → ON DELETE CASCADE).
--
-- Deleting a user cascades to maintenance_schedules, and service_logs.schedule_id is ON DELETE SET NULL.
-- That SET NULL is an UPDATE, so the locked-log guard rejected it and the whole account deletion failed for
-- anyone with a verified report. The guard now lets exactly that change through: schedule_id going to
-- null, nothing else changing, and the referenced schedule already gone. Clients can't hard-delete
-- schedules (no DELETE policy), so only a server-side cascade can reach this path.

create or replace function public.prevent_locked_log_changes()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  unchanged public.service_logs;
begin
  if old.locked_at is null then
    return new;
  end if;

  if old.schedule_id is not null and new.schedule_id is null then
    unchanged := new;
    unchanged.schedule_id := old.schedule_id;
    if to_jsonb(unchanged) = to_jsonb(old)
       and not exists (select 1 from public.maintenance_schedules s where s.id = old.schedule_id) then
      return new;
    end if;
  end if;

  raise exception 'service log % is locked by a verified report', old.id
    using errcode = 'check_violation';
end;
$$;
