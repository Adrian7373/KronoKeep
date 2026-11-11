/**
 * Sends reminder pushes exactly once per (schedule, status, service cycle).
 *
 * The claim is an `insert … on conflict do nothing` into `reminder_log`, done *before* sending, so two
 * overlapping runs can't both send. If every device failed transiently the claim is released so the next
 * hourly run retries; dead tokens are deleted. All I/O is injected (see recompute-due/index.ts).
 */
import { dueData, type DuePushData, type SendOutcome } from "./fcm.ts";
import type { ReminderCandidate } from "./recompute.ts";

export interface ReminderKey {
  schedule_id: string;
  status: string;
  cycle_key: string;
}

export interface DeliveryPorts {
  /** Inserts the keys that aren't logged yet and returns only those (the ones this run owns). */
  claim(rows: Array<ReminderKey & { user_id: string }>): Promise<ReminderKey[]>;
  release(key: ReminderKey): Promise<void>;
  send(token: string, data: DuePushData): Promise<SendOutcome>;
  deleteToken(token: string): Promise<void>;
}

export interface DeliveryStats {
  candidates: number;
  claimed: number;
  sent: number;
  released: number;
  tokensRemoved: number;
}

export const keyOf = (k: ReminderKey) => `${k.schedule_id}\u0000${k.status}\u0000${k.cycle_key}`;

export async function deliverReminders(
  candidates: ReminderCandidate[],
  tokensByUser: Map<string, string[]>,
  ports: DeliveryPorts,
): Promise<DeliveryStats> {
  // Users without a device can't be reached; don't claim, so they're notified once they register one.
  const reachable = candidates.filter((c) => (tokensByUser.get(c.user_id)?.length ?? 0) > 0);
  const stats: DeliveryStats = { candidates: reachable.length, claimed: 0, sent: 0, released: 0, tokensRemoved: 0 };
  if (reachable.length === 0) return stats;

  const claimed = new Set(
    (await ports.claim(reachable.map((c) => ({
      user_id: c.user_id,
      schedule_id: c.schedule_id,
      status: c.status,
      cycle_key: c.cycle_key,
    })))).map(keyOf),
  );
  const dead = new Set<string>();

  for (const c of reachable) {
    if (!claimed.has(keyOf(c))) continue;
    stats.claimed++;
    const tokens = (tokensByUser.get(c.user_id) ?? []).filter((t) => !dead.has(t));
    let delivered = false;
    let rejected = false;
    for (const token of tokens) {
      const outcome = await ports.send(token, dueData(c));
      if (outcome === "sent") delivered = true;
      else if (outcome === "rejected") rejected = true;
      else if (outcome === "unregistered") {
        dead.add(token);
        await ports.deleteToken(token);
        stats.tokensRemoved++;
      }
    }
    if (delivered) {
      stats.sent++;
    } else if (!rejected) {
      // Outage, or every token was dead: release so the next run (or a newly registered device) gets it.
      // A rejected payload would fail forever, so that claim stays.
      await ports.release(c);
      stats.released++;
    }
  }
  return stats;
}
