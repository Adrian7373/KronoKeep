/**
 * Firebase Cloud Messaging HTTP v1 client. Signs the service-account JWT with WebCrypto, so it needs no
 * extra dependency and runs on Deno and Node alike. The service account JSON lives only in the Edge
 * Function secret `FCM_SERVICE_ACCOUNT`; it never goes into the app or the repo.
 */
import type { ReminderCandidate } from "./recompute.ts";

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

/** Data-only payload; the app's FirebaseMessagingService parses exactly these keys (all strings). */
export interface DuePushData {
  [key: string]: string;
  type: "due";
  schedule_id: string;
  asset_id: string;
  asset_name: string;
  task: string;
  status: "due_soon" | "overdue";
  next_due_at: string;
}

/** What to do after a send. `unregistered`: drop the token. `transient`: retry next run. `rejected`: don't retry. */
export type SendOutcome = "sent" | "unregistered" | "transient" | "rejected";

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

export function dueData(r: ReminderCandidate): DuePushData {
  return {
    type: "due",
    schedule_id: r.schedule_id,
    asset_id: r.asset_id,
    asset_name: r.asset_name,
    task: r.task,
    status: r.status,
    next_due_at: r.next_due_at ?? "",
  };
}

/**
 * Data-only (no `notification` block) so the app always handles it, foreground or not, and can dedupe it
 * against the reminders it already posted locally. High priority, and dropped after a day if undelivered:
 * a stale "due soon" is worse than none, and the next hourly run re-evaluates anyway.
 */
export function buildMessage(token: string, data: DuePushData) {
  return {
    message: {
      token,
      data,
      android: { priority: "HIGH", ttl: "86400s" },
    },
  };
}

export function classifySendError(status: number, body: string): SendOutcome {
  let code = "";
  try {
    const err = JSON.parse(body)?.error;
    const details: Array<{ errorCode?: string }> = err?.details ?? [];
    code = details.find((d) => d.errorCode)?.errorCode ?? err?.status ?? "";
  } catch {
    // not JSON; classify by status alone
  }
  if (code === "UNREGISTERED" || status === 404) return "unregistered";
  // A malformed or foreign token is as dead as an unregistered one.
  if (code === "INVALID_ARGUMENT" || code === "SENDER_ID_MISMATCH") return "unregistered";
  if (status === 429 || status >= 500 || code === "UNAVAILABLE" || code === "INTERNAL" || code === "QUOTA_EXCEEDED") {
    return "transient";
  }
  return "rejected";
}

export class FcmClient {
  private readonly account: ServiceAccount;
  private readonly fetchFn: typeof fetch;
  private accessToken: string | null = null;
  private expiresAtMs = 0;

  constructor(account: ServiceAccount, fetchFn: typeof fetch = fetch) {
    this.account = account;
    this.fetchFn = fetchFn;
  }

  static fromEnv(json: string | undefined): FcmClient | null {
    if (!json) return null;
    return new FcmClient(JSON.parse(json) as ServiceAccount);
  }

  async send(token: string, data: DuePushData): Promise<SendOutcome> {
    let access: string;
    try {
      access = await this.token();
    } catch {
      return "transient";
    }
    let res: Response;
    try {
      res = await this.fetchFn(`https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
        body: JSON.stringify(buildMessage(token, data)),
      });
    } catch {
      return "transient";
    }
    if (res.ok) {
      await res.body?.cancel();
      return "sent";
    }
    if (res.status === 401) this.accessToken = null; // expired early; the next call mints a new one
    return classifySendError(res.status, await res.text());
  }

  private async token(): Promise<string> {
    if (this.accessToken && Date.now() < this.expiresAtMs - 60_000) return this.accessToken;
    const tokenUri = this.account.token_uri ?? DEFAULT_TOKEN_URI;
    const assertion = await signJwt(this.account, tokenUri, Math.floor(Date.now() / 1000));
    const res = await this.fetchFn(tokenUri, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!res.ok) throw new Error(`token exchange failed: ${res.status}`);
    const json = await res.json() as { access_token: string; expires_in: number };
    this.accessToken = json.access_token;
    this.expiresAtMs = Date.now() + json.expires_in * 1000;
    return json.access_token;
  }
}

/** RS256 JWT for the OAuth 2.0 JWT-bearer grant. `nowSec` is a parameter so tests are deterministic. */
export async function signJwt(account: ServiceAccount, audience: string, nowSec: number): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = { iss: account.client_email, scope: SCOPE, aud: audience, iat: nowSec, exp: nowSec + 3600 };
  const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(input));
  return `${input}.${b64url(new Uint8Array(sig))}`;
}

function pemToDer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\\n/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

function b64url(input: string | Uint8Array): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
