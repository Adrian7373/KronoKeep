package com.kronokeep.app.domain.presets

import com.kronokeep.app.domain.model.AssetCategory

/** One suggested schedule. Intervals are editable after they're added. */
data class PresetTask(val task: String, val intervalDays: Int? = null, val intervalKm: Int? = null)

enum class Preset(val label: String, val category: AssetCategory, val tasks: List<PresetTask>) {
    /** PH automatic scooters (Click / Mio / NMAX / Aerox class). Oil every ~1,500 km or 2 months. */
    SCOOTER(
        "Scooter",
        AssetCategory.VEHICLE,
        listOf(
            PresetTask("Engine oil change", intervalDays = 60, intervalKm = 1_500),
            PresetTask("Gear oil change", intervalKm = 5_000),
            PresetTask("CVT cleaning", intervalKm = 5_000),
            PresetTask("Air filter replacement", intervalKm = 10_000),
        ),
    ),

    /** Routine car PMS (preventive maintenance service). */
    CAR(
        "Car",
        AssetCategory.VEHICLE,
        listOf(PresetTask("PMS (preventive maintenance)", intervalDays = 180, intervalKm = 5_000)),
    ),

    /** Split-type aircon. The guideline is 3–6 months; 3 suits daily use in PH heat and city dust. */
    AIRCON("Split-type aircon", AssetCategory.APPLIANCE, listOf(PresetTask("Aircon cleaning", intervalDays = 90))),

    WATER_FILTER(
        "Water filter",
        AssetCategory.APPLIANCE,
        listOf(PresetTask("Filter cartridge replacement", intervalDays = 180)),
    ),
    ;

    companion object {
        /** Presets offered for a category. Electronics track the warranty only. */
        fun forCategory(category: AssetCategory): List<Preset> = entries.filter { it.category == category }

        private val SCOOTER_HINTS = listOf("click", "mio", "nmax", "aerox", "scooter", "beat", "pcx", "adv", "burgman", "skydrive")
        private val CAR_HINTS = listOf("vios", "civic", "city", "mirage", "innova", "fortuner", "car", "sedan", "suv", "hatch")
        private val AIRCON_HINTS = listOf("aircon", "air con", "air-con", "split", "inverter", "a/c")
        private val WATER_HINTS = listOf("water", "filter", "purifier", "dispenser")

        /** Best guess from what the user typed, or null when nothing matches. Only a suggestion; the user can switch. */
        fun suggest(category: AssetCategory, name: String, brand: String?, model: String?): Preset? {
            val text = listOfNotNull(name, brand, model).joinToString(" ").lowercase()
            fun matches(hints: List<String>) = hints.any { hint -> Regex("\\b${Regex.escape(hint)}\\b").containsMatchIn(text) }
            return when (category) {
                AssetCategory.VEHICLE -> when {
                    matches(SCOOTER_HINTS) -> SCOOTER
                    matches(CAR_HINTS) -> CAR
                    else -> null
                }
                AssetCategory.APPLIANCE -> when {
                    matches(AIRCON_HINTS) -> AIRCON
                    matches(WATER_HINTS) -> WATER_FILTER
                    else -> null
                }
                AssetCategory.ELECTRONIC -> null
            }
        }
    }
}
