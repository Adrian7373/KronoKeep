import { test } from "node:test";
import assert from "node:assert/strict";
import { buildMessage, classifySendError, FcmClient, type ServiceAccount, signJwt } from "./fcm.ts";

const data = {
  type: "due" as const,
  schedule_id: "s1",
  asset_id: "a1",
  asset_name: "Click 125",
  task: "Engine oil",
  status: "overdue" as const,
  next_due_at: "",
};

async function testAccount(): Promise<{ account: ServiceAccount; publicKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  ) as CryptoKeyPair;
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  let bin = "";
  for (const b of der) bin += String.fromCharCode(b);
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(bin).replace(/(.{64})/g, "$1\n")}\n-----END PRIVATE KEY-----\n`;
  return {
    account: { project_id: "kk-test", client_email: "push@kk-test.iam.gserviceaccount.com", private_key: pem },
    publicKey: pair.publicKey,
  };
}

const fromB64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

test("message is data-only, high priority, one-day TTL", () => {
  const m = buildMessage("tok", data);
  assert.deepEqual(m, { message: { token: "tok", data, android: { priority: "HIGH", ttl: "86400s" } } });
  assert.equal("notification" in m.message, false);
});

test("error classification", () => {
  const fcmError = (status: string, errorCode?: string) =>
    JSON.stringify({ error: { status, details: errorCode ? [{ "@type": "x", errorCode }] : [] } });
  assert.equal(classifySendError(404, fcmError("NOT_FOUND", "UNREGISTERED")), "unregistered");
  assert.equal(classifySendError(400, fcmError("INVALID_ARGUMENT", "INVALID_ARGUMENT")), "unregistered");
  assert.equal(classifySendError(403, fcmError("PERMISSION_DENIED", "SENDER_ID_MISMATCH")), "unregistered");
  assert.equal(classifySendError(429, fcmError("RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED")), "transient");
  assert.equal(classifySendError(503, "<html>"), "transient");
  assert.equal(classifySendError(403, fcmError("PERMISSION_DENIED")), "rejected");
});

test("JWT is RS256-signed with the right claims", async () => {
  const { account, publicKey } = await testAccount();
  const jwt = await signJwt(account, "https://oauth2.googleapis.com/token", 1_700_000_000);
  const [h, c, s] = jwt.split(".");
  assert.deepEqual(JSON.parse(new TextDecoder().decode(fromB64url(h))), { alg: "RS256", typ: "JWT" });
  assert.deepEqual(JSON.parse(new TextDecoder().decode(fromB64url(c))), {
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: 1_700_000_000,
    exp: 1_700_003_600,
  });
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, fromB64url(s), new TextEncoder().encode(`${h}.${c}`));
  assert.ok(ok);
});

test("client exchanges the token once, sends, and maps failures", async () => {
  const { account } = await testAccount();
  const calls: string[] = [];
  const responses = [
    new Response(JSON.stringify({ access_token: "at", expires_in: 3600 })),
    new Response("{}"),
    new Response(JSON.stringify({ error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }), { status: 404 }),
  ];
  const fakeFetch = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push(`${init?.method} ${url}`);
    if (String(url).includes("messages:send")) {
      assert.equal((init!.headers as Record<string, string>).Authorization, "Bearer at");
    }
    return responses.shift()!;
  }) as typeof fetch;
  const client = new FcmClient(account, fakeFetch);
  assert.equal(await client.send("t1", data), "sent");
  assert.equal(await client.send("t2", data), "unregistered");
  assert.deepEqual(calls, [
    "POST https://oauth2.googleapis.com/token",
    "POST https://fcm.googleapis.com/v1/projects/kk-test/messages:send",
    "POST https://fcm.googleapis.com/v1/projects/kk-test/messages:send",
  ]);
});

test("network failure is transient", async () => {
  const { account } = await testAccount();
  const client = new FcmClient(account, (async () => {
    throw new TypeError("offline");
  }) as typeof fetch);
  assert.equal(await client.send("t1", data), "transient");
});
