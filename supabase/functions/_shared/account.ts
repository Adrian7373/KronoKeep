// Pure helpers for delete-account. No Deno or Supabase imports, so Node's test runner can load them.

/** One entry from Storage `list()`: folders come back with `id: null`. */
export interface StorageEntry {
  name: string;
  id: string | null;
}

/** Lists one page of a folder (no leading/trailing slash; "" is the bucket root). */
export type ListPage = (folder: string, offset: number, limit: number) => Promise<StorageEntry[]>;

export const LIST_PAGE = 1000;
export const REMOVE_BATCH = 1000;
const MAX_DEPTH = 8;

/**
 * Every object path under `root`, depth first. Paths here are `{user}/{asset}/{file}`, but folders are
 * followed to any depth (bounded) so a stray nested object can't survive account deletion.
 */
export async function collectObjectPaths(list: ListPage, root: string, depth = 0): Promise<string[]> {
  if (depth > MAX_DEPTH) throw new Error(`storage folder nesting deeper than ${MAX_DEPTH} under ${root}`);
  const paths: string[] = [];
  for (let offset = 0; ; offset += LIST_PAGE) {
    const page = await list(root, offset, LIST_PAGE);
    for (const entry of page) {
      // Storage shows an empty-folder marker; it isn't an object that can be removed.
      if (entry.name === ".emptyFolderPlaceholder") continue;
      const path = root ? `${root}/${entry.name}` : entry.name;
      if (entry.id === null) paths.push(...await collectObjectPaths(list, path, depth + 1));
      else paths.push(path);
    }
    if (page.length < LIST_PAGE) return paths;
  }
}

export function chunk<T>(items: readonly T[], size: number): T[][] {
  if (size < 1) throw new Error("chunk size must be positive");
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/** The password from `{"password": "..."}`, or null when it's missing, blank or absurdly long. */
export function readPassword(body: unknown): string | null {
  const password = (body as { password?: unknown } | null)?.password;
  if (typeof password !== "string" || password.length === 0 || password.length > 1024) return null;
  return password;
}

export type PasswordCheck = "ok" | "invalid_password" | "rate_limited" | "error";

/** Classifies the Auth answer to the re-authentication sign-in. */
export function classifyPasswordCheck(err: { status?: number; code?: string } | null): PasswordCheck {
  if (!err) return "ok";
  if (err.status === 429 || err.code === "over_request_rate_limit") return "rate_limited";
  if (err.code === "invalid_credentials" || err.status === 400) return "invalid_password";
  return "error";
}
