package com.kronokeep.app.domain.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

/** Runs every case in `engine-fixtures/`. The TypeScript engine runs the same files. */
class EngineFixturesTest {

    private val dir: File = listOf(File("../engine-fixtures"), File("engine-fixtures")).first { it.isDirectory }
    private val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test fun `there are fixtures to run`() {
        assertTrue("expected at least 10 fixtures in ${dir.absolutePath}", files.size >= 10)
    }

    @Test fun `kotlin engine passes every shared fixture`() {
        val failures = files.mapNotNull { file ->
            runCatching { check(Json.parseToJsonElement(file.readText()).jsonObject) }
                .exceptionOrNull()
                ?.let { "${file.name}: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(case: JsonObject) {
        val now = Instant.parse(case.str("now")!!)
        val s = case.getValue("schedule").jsonObject
        val u = case.getValue("usage").jsonObject
        val terms = ScheduleTerms(
            intervalDays = s.int("intervalDays"),
            intervalKm = s.int("intervalKm"),
            lastDoneAt = s.str("lastDoneAt")?.let(Instant::parse),
            lastDoneKm = s.int("lastDoneKm"),
        )
        val usage = UsageInput(
            readings = u.getValue("readings").jsonArray.map {
                OdometerPoint(km = it.jsonObject.int("km")!!, at = Instant.parse(it.jsonObject.str("at")!!))
            },
            currentOdometerKm = u.int("currentOdometerKm"),
            weeklyKmBaseline = u.int("weeklyKmBaseline"),
        )
        val e = case.getValue("expected").jsonObject
        val p = MaintenanceEngine.evaluate(terms, usage, now)

        assertEquals("rateSource", e.str("rateSource"), p.rate.source.wire)
        assertDouble("kmPerDay", e.dbl("kmPerDay"), p.rate.kmPerDay)
        val expectedDue = e.str("nextDueAt")?.let(Instant::parse)
        if (expectedDue == null) {
            assertNull("nextDueAt", p.nextDueAt)
        } else {
            assertNotNull("nextDueAt", p.nextDueAt)
            val drift = Duration.between(expectedDue, p.nextDueAt).abs()
            assertTrue("nextDueAt expected $expectedDue but was ${p.nextDueAt}", drift <= Duration.ofSeconds(1))
        }
        assertEquals("nextDueKm", e.int("nextDueKm"), p.nextDueKm)
        assertEquals("trigger", e.str("trigger"), p.trigger?.wire)
        assertEquals("status", e.str("status"), p.status.wire)
        assertDouble("confidence", e.dbl("confidence"), p.confidence)
        assertEquals("uncertaintyDays", e.int("uncertaintyDays"), p.uncertaintyDays)
        assertEquals("projectedKm", e.int("projectedKm"), p.projectedKm)
    }

    private fun assertDouble(what: String, expected: Double?, actual: Double?) {
        if (expected == null) return assertNull(what, actual)
        assertNotNull(what, actual)
        assertEquals(what, expected, actual!!, 1e-6)
    }

    private fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }
    private fun JsonObject.str(key: String): String? = value(key)?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = value(key)?.jsonPrimitive?.int
    private fun JsonObject.dbl(key: String): Double? = value(key)?.jsonPrimitive?.double
}
