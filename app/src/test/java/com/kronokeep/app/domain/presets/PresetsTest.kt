package com.kronokeep.app.domain.presets

import com.kronokeep.app.domain.model.AssetCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    @Test fun `scooter preset matches the agreed PH intervals`() {
        val byTask = Preset.SCOOTER.tasks.associateBy { it.task }
        assertEquals(PresetTask("Engine oil change", 60, 1_500), byTask.getValue("Engine oil change"))
        assertEquals(5_000, byTask.getValue("Gear oil change").intervalKm)
        assertEquals(5_000, byTask.getValue("CVT cleaning").intervalKm)
        assertEquals(10_000, byTask.getValue("Air filter replacement").intervalKm)
    }

    @Test fun `car, aircon and water filter presets`() {
        assertEquals(PresetTask("PMS (preventive maintenance)", 180, 5_000), Preset.CAR.tasks.single())
        assertEquals(90, Preset.AIRCON.tasks.single().intervalDays)
        assertEquals(180, Preset.WATER_FILTER.tasks.single().intervalDays)
    }

    @Test fun `every preset task has an interval and km only on vehicles`() {
        for (p in Preset.entries) for (t in p.tasks) {
            assertTrue(t.intervalDays != null || t.intervalKm != null)
            if (p.category != AssetCategory.VEHICLE) assertNull(t.intervalKm)
        }
    }

    @Test fun `electronics get no presets`() {
        assertTrue(Preset.forCategory(AssetCategory.ELECTRONIC).isEmpty())
        assertNull(Preset.suggest(AssetCategory.ELECTRONIC, "Aircon remote", null, null))
    }

    @Test fun `suggests from name, brand or model`() {
        assertEquals(Preset.SCOOTER, Preset.suggest(AssetCategory.VEHICLE, "My ride", "Yamaha", "NMAX 155"))
        assertEquals(Preset.SCOOTER, Preset.suggest(AssetCategory.VEHICLE, "Honda Click 125i", null, null))
        assertEquals(Preset.CAR, Preset.suggest(AssetCategory.VEHICLE, "Toyota Vios", null, null))
        assertEquals(Preset.AIRCON, Preset.suggest(AssetCategory.APPLIANCE, "Bedroom aircon", "Carrier", null))
        assertEquals(Preset.WATER_FILTER, Preset.suggest(AssetCategory.APPLIANCE, "Kitchen water purifier", null, null))
    }

    @Test fun `matches whole words only`() {
        // "Mio" must not match "Camioneta", and an unknown vehicle gets no guess.
        assertNull(Preset.suggest(AssetCategory.VEHICLE, "Camioneta", null, null))
        assertNull(Preset.suggest(AssetCategory.APPLIANCE, "Refrigerator", "Samsung", null))
    }
}
