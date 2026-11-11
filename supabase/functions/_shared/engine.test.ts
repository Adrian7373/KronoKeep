// Runs every case in engine-fixtures/ against the TypeScript engine (the Kotlin engine runs the same files
// in EngineFixturesTest). Only node: built-ins, so it runs on both runtimes:
//   node --test supabase/functions/_shared/engine.test.ts      (Node 22.18+, type stripping)
//   deno test --allow-read supabase/functions/_shared/engine.test.ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { evaluate, type ScheduleTerms, type UsageInput } from "./engine.ts";

const fixturesDir = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..", "engine-fixtures");
const files = readdirSync(fixturesDir).filter((f) => f.endsWith(".json")).sort();

const date = (s: string | null | undefined): Date | null => (s == null ? null : new Date(s));

function assertClose(what: string, expected: number | null, actual: number | null) {
  if (expected === null) return assert.equal(actual, null, what);
  assert.notEqual(actual, null, what);
  assert.ok(Math.abs((actual as number) - expected) <= 1e-6, `${what}: expected ${expected} but was ${actual}`);
}

test("there are fixtures to run", () => {
  assert.ok(files.length >= 10, `expected at least 10 fixtures in ${fixturesDir}`);
});

for (const file of files) {
  test(`fixture ${file}`, () => {
    const c = JSON.parse(readFileSync(join(fixturesDir, file), "utf8"));
    const s = c.schedule;
    const u = c.usage;
    const terms: ScheduleTerms = {
      intervalDays: s.intervalDays ?? null,
      intervalKm: s.intervalKm ?? null,
      lastDoneAt: date(s.lastDoneAt),
      lastDoneKm: s.lastDoneKm ?? null,
    };
    const usage: UsageInput = {
      readings: u.readings.map((r: { km: number; at: string }) => ({ km: r.km, at: new Date(r.at) })),
      currentOdometerKm: u.currentOdometerKm ?? null,
      weeklyKmBaseline: u.weeklyKmBaseline ?? null,
    };
    const e = c.expected;
    const p = evaluate(terms, usage, new Date(c.now));

    assert.equal(p.rate.source, e.rateSource, "rateSource");
    assertClose("kmPerDay", e.kmPerDay ?? null, p.rate.kmPerDay);
    if (e.nextDueAt == null) {
      assert.equal(p.nextDueAt, null, "nextDueAt");
    } else {
      assert.notEqual(p.nextDueAt, null, "nextDueAt");
      const drift = Math.abs(p.nextDueAt!.getTime() - new Date(e.nextDueAt).getTime());
      assert.ok(drift <= 1000, `nextDueAt expected ${e.nextDueAt} but was ${p.nextDueAt!.toISOString()}`);
    }
    assert.equal(p.nextDueKm, e.nextDueKm ?? null, "nextDueKm");
    assert.equal(p.trigger, e.trigger ?? null, "trigger");
    assert.equal(p.status, e.status, "status");
    assertClose("confidence", e.confidence, p.confidence);
    assert.equal(p.uncertaintyDays, e.uncertaintyDays ?? null, "uncertaintyDays");
    assert.equal(p.projectedKm, e.projectedKm ?? null, "projectedKm");
  });
}
