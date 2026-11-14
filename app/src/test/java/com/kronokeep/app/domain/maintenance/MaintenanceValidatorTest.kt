package com.kronokeep.app.domain.maintenance

import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MaintenanceValidatorTest {
    private val now = Instant.parse("2026-11-14T02:00:00Z")
    private val v = AssetCategory.VEHICLE
    private val good = ScheduleDraft("Engine oil change", 60, 1_500, now, 10_000)

    @Test fun `valid schedule has no errors`() {
        assertTrue(MaintenanceValidator.validateSchedule(good, v, 10_000, now).isEmpty())
    }

    @Test fun `task is required and bounded`() {
        assertEquals(MaintenanceError.Required, MaintenanceValidator.validateSchedule(good.copy(task = " "), v, null, now)[ScheduleField.TASK])
        assertEquals(
            MaintenanceError.TooLong(120),
            MaintenanceValidator.validateSchedule(good.copy(task = "x".repeat(121)), v, null, now)[ScheduleField.TASK],
        )
    }

    @Test fun `needs at least one interval`() {
        val errors = MaintenanceValidator.validateSchedule(good.copy(intervalDays = null, intervalKm = null), v, null, now)
        assertEquals(MaintenanceError.NoInterval, errors[ScheduleField.INTERVAL_DAYS])
    }

    @Test fun `km interval is ignored for appliances so a days interval is needed`() {
        val errors = MaintenanceValidator.validateSchedule(
            ScheduleDraft("Aircon cleaning", null, 5_000, now, null),
            AssetCategory.APPLIANCE,
            null,
            now,
        )
        assertEquals(MaintenanceError.NoInterval, errors[ScheduleField.INTERVAL_DAYS])
        assertNull(errors[ScheduleField.INTERVAL_KM])
    }

    @Test fun `intervals must be positive`() {
        val errors = MaintenanceValidator.validateSchedule(good.copy(intervalDays = 0, intervalKm = -5), v, null, now)
        assertEquals(MaintenanceError.NotPositive, errors[ScheduleField.INTERVAL_DAYS])
        assertEquals(MaintenanceError.NotPositive, errors[ScheduleField.INTERVAL_KM])
    }

    @Test fun `last done can't be in the future or above the odometer`() {
        val errors = MaintenanceValidator.validateSchedule(
            good.copy(lastDoneAt = now.plus(Duration.ofDays(1)), lastDoneKm = 12_000),
            v,
            10_000,
            now,
        )
        assertEquals(MaintenanceError.InFuture, errors[ScheduleField.LAST_DONE_AT])
        assertEquals(MaintenanceError.AboveOdometer(10_000), errors[ScheduleField.LAST_DONE_KM])
    }

    @Test fun `km schedule needs last done km`() {
        assertEquals(
            MaintenanceError.Required,
            MaintenanceValidator.validateSchedule(good.copy(lastDoneKm = null), v, 10_000, now)[ScheduleField.LAST_DONE_KM],
        )
    }

    private val visit = ServiceVisit(now, 11_000, Peso(150_000), "Shop", null, listOf("oil"))

    @Test fun `valid visit has no errors`() {
        assertTrue(MaintenanceValidator.validateVisit(visit, v, setOf("oil"), now).isEmpty())
    }

    @Test fun `km task needs an odometer value`() {
        val errors = MaintenanceValidator.validateVisit(visit.copy(odometerKm = null), v, setOf("oil"), now)
        assertEquals(MaintenanceError.OdometerNeeded, errors[VisitField.ODOMETER])
        // A days-only task doesn't.
        assertTrue(MaintenanceValidator.validateVisit(visit.copy(odometerKm = null), v, emptySet(), now).isEmpty())
    }

    @Test fun `ad hoc repair needs a description`() {
        val errors = MaintenanceValidator.validateVisit(visit.copy(scheduleIds = emptyList(), notes = null), v, emptySet(), now)
        assertEquals(MaintenanceError.NothingLogged, errors[VisitField.NOTES])
    }

    @Test fun `visit can't be in the future`() {
        val errors = MaintenanceValidator.validateVisit(visit.copy(performedAt = now.plusSeconds(3_600)), v, setOf("oil"), now)
        assertEquals(MaintenanceError.InFuture, errors[VisitField.PERFORMED_AT])
    }

    @Test fun `odometer can't go backwards`() {
        assertEquals(MaintenanceError.BelowLastReading(10_000), MaintenanceValidator.validateOdometer(9_999, 10_000))
        assertNull(MaintenanceValidator.validateOdometer(10_000, 10_000))
        assertEquals(MaintenanceError.Negative, MaintenanceValidator.validateOdometer(-1, null))
    }
}
