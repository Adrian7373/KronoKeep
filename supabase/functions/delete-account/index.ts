// delete-account: permanently deletes the caller's account and everything it owns.
//
// POST {"password": "..."} with the user's access token (JWT verification stays on).
// 1. Re-checks the password with a throwaway Auth client, so a stolen or left-open session alone can't
//    delete the account.
// 2. Removes every storage object under {user_id}/ in the `documents` and `reports` buckets. Storage
//    objects aren't tied to auth.users, so they'd otherwise outlive the account.
// 3. Hard-deletes the auth user. Every public table references auth.users with ON DELETE CASCADE, and the
//    locked-log guard only fires on UPDATE, so assets, schedules, logs (locked ones too), readings,
//    documents, reports, device tokens and reminder_log rows all go in one statement. Verify links of the
//    user's reports answer NOT FOUND afterwards.
// Steps run in this order so a failure leaves the account in place and the call can simply be repeated.
//
// 200 {deleted: true, objects_removed}
// Errors {error, message}: unauthorized 401, invalid_password 403, rate_limited 429, bad_request 400,
// internal 500.
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import { chunk, classifyPasswordCheck, collectObjectPaths, readPassword, REMOVE_BATCH } from "../_shared/account.ts";

const BUCKETS = ["documents", "reports"] as const;

Deno.serve(async (req) => {
  if (req.method !== "POST") return error("method_not_allowed", "Use POST.", 405);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const db = serviceClient(supabaseUrl, serviceKey);

  const jwt = /^Bearer\s+(.+)$/i.exec(req.headers.get("Authorization") ?? "")?.[1]?.trim();
  if (!jwt) return error("unauthorized", "Sign in to delete your account.", 401);
  const { data: auth, error: authError } = await db.auth.getUser(jwt);
  const user = auth?.user;
  if (authError || !user?.id) return error("unauthorized", "Sign in to delete your account.", 401);
  if (!user.email) return error("bad_request", "This account has no email to confirm with.", 400);

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    body = null;
  }
  const password = readPassword(body);
  if (!password) return error("bad_request", "Enter your password to confirm.", 400);

  // A separate client: signing in stores a session on the client, which must not leak into `db`.
  const verifier = serviceClient(supabaseUrl, serviceKey);
  const { error: signInError } = await verifier.auth.signInWithPassword({ email: user.email, password });
  switch (classifyPasswordCheck(signInError ? { status: signInError.status, code: signInError.code } : null)) {
    case "ok":
      break;
    case "invalid_password":
      return error("invalid_password", "That password isn't right.", 403);
    case "rate_limited":
      return error("rate_limited", "Too many attempts. Wait a minute, then try again.", 429);
    default:
      console.error("delete-account: password check failed", signInError);
      return error("internal", "Couldn't delete the account. Try again later.", 500);
  }

  try {
    let removed = 0;
    for (const bucket of BUCKETS) removed += await purgeFolder(db, bucket, user.id);

    const { error: deleteError } = await db.auth.admin.deleteUser(user.id);
    if (deleteError) throw deleteError;

    console.log("delete-account: deleted", user.id, "objects", removed);
    return json({ deleted: true, objects_removed: removed }, 200);
  } catch (e) {
    console.error("delete-account failed", user.id, e);
    return error("internal", "Couldn't delete the account. Try again later.", 500);
  }
});

async function purgeFolder(db: SupabaseClient, bucket: string, userId: string): Promise<number> {
  const storage = db.storage.from(bucket);
  const paths = await collectObjectPaths(async (folder, offset, limit) => {
    const { data, error } = await storage.list(folder, { offset, limit, sortBy: { column: "name", order: "asc" } });
    if (error) throw error;
    return (data ?? []).map((e) => ({ name: e.name, id: e.id ?? null }));
  }, userId);
  for (const batch of chunk(paths, REMOVE_BATCH)) {
    const { error } = await storage.remove(batch);
    if (error) throw error;
  }
  return paths.length;
}

function serviceClient(url: string, key: string): SupabaseClient {
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

function error(code: string, message: string, status: number): Response {
  return json({ error: code, message }, status);
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
