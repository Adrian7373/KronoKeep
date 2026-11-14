-- Security advisor fixes after the first deploy.

-- pg_net was created in `public` (lint 0014). Its functions live in the `net` schema either way; this only
-- moves the extension's registration out of the exposed API schema. pg_net doesn't support SET SCHEMA, so
-- recreate it (safe: nothing is queued between hourly runs, and the cron job calls net.http_post by name).
drop extension if exists pg_net;
create extension pg_net with schema extensions;

-- owns_asset is only meant for RLS policies (lint 0028). Anonymous callers have no table access, so they
-- don't need it. `authenticated` keeps EXECUTE: policies run as the caller, and the function only answers
-- "do I own this asset?" for the caller themselves.
revoke execute on function public.owns_asset(uuid) from public, anon;
grant execute on function public.owns_asset(uuid) to authenticated;
