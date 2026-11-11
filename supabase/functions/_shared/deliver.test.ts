import { test } from "node:test";
import assert from "node:assert/strict";
import { deliverReminders, type DeliveryPorts, keyOf, type ReminderKey } from "./deliver.ts";
import type { DuePushData, SendOutcome } from "./fcm.ts";
import type { ReminderCandidate } from "./recompute.ts";

const candidate = (over: Partial<ReminderCandidate> = {}): ReminderCandidate => ({
  user_id: "u1",
  schedule_id: "s1",
  asset_id: "a1",
  asset_name: "Click 125",
  task: "Engine oil",
  status: "overdue",
  next_due_at: "2026-11-10T00:00:00.000Z",
  cycle_key: "2026-09-01T00:00:00.000Z|||60|",
  ...over,
});

/** In-memory reminder_log + FCM. */
function fakePorts(outcomes: Record<string, SendOutcome> = {}) {
  const log = new Set<string>();
  const sent: Array<{ token: string; data: DuePushData }> = [];
  const deleted: string[] = [];
  const ports: DeliveryPorts = {
    async claim(rows) {
      const won: ReminderKey[] = [];
      for (const r of rows) {
        if (log.has(keyOf(r))) continue;
        log.add(keyOf(r));
        won.push({ schedule_id: r.schedule_id, status: r.status, cycle_key: r.cycle_key });
      }
      return won;
    },
    async release(k) {
      log.delete(keyOf(k));
    },
    async send(token, data) {
      sent.push({ token, data });
      return outcomes[token] ?? "sent";
    },
    async deleteToken(token) {
      deleted.push(token);
    },
  };
  return { ports, log, sent, deleted };
}

test("sends once per key across runs, to every device of the user", async () => {
  const f = fakePorts();
  const tokens = new Map([["u1", ["t1", "t2"]]]);
  const first = await deliverReminders([candidate()], tokens, f.ports);
  const second = await deliverReminders([candidate()], tokens, f.ports);
  assert.equal(first.sent, 1);
  assert.equal(second.claimed, 0);
  assert.deepEqual(f.sent.map((s) => s.token), ["t1", "t2"]);
  assert.deepEqual(f.sent[0].data, {
    type: "due",
    schedule_id: "s1",
    asset_id: "a1",
    asset_name: "Click 125",
    task: "Engine oil",
    status: "overdue",
    next_due_at: "2026-11-10T00:00:00.000Z",
  });
});

test("moving from due_soon to overdue, or a new cycle, notifies again", async () => {
  const f = fakePorts();
  const tokens = new Map([["u1", ["t1"]]]);
  await deliverReminders([candidate({ status: "due_soon" })], tokens, f.ports);
  await deliverReminders([candidate({ status: "overdue" })], tokens, f.ports);
  await deliverReminders([candidate({ status: "overdue", cycle_key: "next" })], tokens, f.ports);
  assert.equal(f.sent.length, 3);
});

test("users without devices are not claimed, so they're notified after registering", async () => {
  const f = fakePorts();
  const none = await deliverReminders([candidate()], new Map(), f.ports);
  assert.deepEqual(none, { candidates: 0, claimed: 0, sent: 0, released: 0, tokensRemoved: 0 });
  const later = await deliverReminders([candidate()], new Map([["u1", ["t1"]]]), f.ports);
  assert.equal(later.sent, 1);
});

test("a transient failure on every device releases the claim for the next run", async () => {
  const f = fakePorts({ t1: "transient" });
  const stats = await deliverReminders([candidate()], new Map([["u1", ["t1"]]]), f.ports);
  assert.equal(stats.released, 1);
  assert.equal(f.log.size, 0);
});

test("dead tokens are deleted once, and the claim is released if nobody got it", async () => {
  const f = fakePorts({ dead: "unregistered" });
  const stats = await deliverReminders(
    [candidate(), candidate({ schedule_id: "s2" })],
    new Map([["u1", ["dead"]]]),
    f.ports,
  );
  assert.deepEqual(f.deleted, ["dead"]);
  assert.equal(f.sent.length, 1, "the second reminder skips the token already found dead");
  assert.equal(stats.released, 2);
});

test("one live device is enough to keep the claim; a rejected payload is not retried", async () => {
  const partial = fakePorts({ t1: "transient" });
  await deliverReminders([candidate()], new Map([["u1", ["t1", "t2"]]]), partial.ports);
  assert.equal(partial.log.size, 1);

  const rejected = fakePorts({ t1: "rejected" });
  const stats = await deliverReminders([candidate()], new Map([["u1", ["t1"]]]), rejected.ports);
  assert.equal(stats.released, 0);
  assert.equal(rejected.log.size, 1);
});
