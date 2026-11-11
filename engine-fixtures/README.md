# Engine fixtures

Golden cases for the maintenance engine. The Kotlin engine (`app/src/main/java/com/kronokeep/app/domain/engine/`) and the TypeScript engine (`supabase/functions/_shared/engine.ts`) must both pass every file here: `EngineFixturesTest` (Gradle) and `node --test supabase/functions/_shared/engine.test.ts` run them. If you change the engine rules, change both engines and these fixtures in the same branch.

Each file is one case:

```jsonc
{
  "name": "…",
  "description": "why this case exists",
  "now": "ISO-8601 instant",
  "schedule": { "intervalDays": 60, "intervalKm": 1500, "lastDoneAt": "…", "lastDoneKm": 10000 },
  "usage": {
    "currentOdometerKm": 10000,
    "weeklyKmBaseline": 350,
    "readings": [{ "km": 10000, "at": "…" }]
  },
  "expected": {
    "rateSource": "ewma | baseline | none",
    "kmPerDay": 50.0,             // null when rateSource is none
    "nextDueAt": "…",             // null when neither trigger can be dated
    "nextDueKm": 11500,
    "trigger": "calendar | usage",// null with nextDueAt
    "status": "ok | due_soon | overdue",
    "confidence": 0.3,
    "uncertaintyDays": 12,        // ± days; 0 for exact dates, null when undated
    "projectedKm": 10650          // null without any odometer value
  }
}
```

Comparison: doubles within `1e-6`, `nextDueAt` within 1 second, everything else exact. Expected values were worked out by hand or computed separately from the engine code, not copied from its output.
