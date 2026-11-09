package com.kronokeep.app.ui.service

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kronokeep.app.domain.maintenance.LogServiceUseCase
import com.kronokeep.app.domain.maintenance.ObserveAssetMaintenanceUseCase
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.testing.InMemoryAssetRepository
import com.kronokeep.app.testing.InMemoryMaintenanceRepository
import com.kronokeep.app.ui.theme.KronoKeepTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class LogServiceFlowTest {

    @get:Rule val compose = createComposeRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-14T02:00:00Z"), ZoneOffset.UTC)
    private val assets = InMemoryAssetRepository()
    private val maintenance = InMemoryMaintenanceRepository()
    private var savedCount: Int? = null

    private lateinit var assetId: String
    private lateinit var oilId: String
    private lateinit var cvtId: String

    private fun launch(preselect: Boolean = true) {
        runBlocking {
            assetId = assets.create(AssetDraft(AssetCategory.VEHICLE, "Click 125i", currentOdometerKm = 10_000, weeklyKmBaseline = 140))
            val ids = maintenance.addSchedules(
                assetId,
                listOf(
                    ScheduleDraft("Engine oil change", 60, 1_500, clock.instant(), 10_000),
                    ScheduleDraft("CVT cleaning", null, 5_000, clock.instant(), 10_000),
                ),
            )
            oilId = ids[0]
            cvtId = ids[1]
        }
        val vm = LogServiceViewModel(
            assetId = assetId,
            preselected = oilId.takeIf { preselect },
            assets = assets,
            observeMaintenance = ObserveAssetMaintenanceUseCase(maintenance, clock),
            logService = LogServiceUseCase(assets, maintenance, clock),
            clock = clock,
            zone = ZoneOffset.UTC,
        )
        compose.setContent {
            KronoKeepTheme { LogServiceScreen(viewModel = vm, onDone = { savedCount = it }, onClose = {}) }
        }
    }

    @Test fun oneVisitCoversTwoTasksAndNeedsTheOdometer() {
        launch()
        compose.onNodeWithTag(LogServiceTags.task(oilId)).assertIsOn()

        compose.onNodeWithTag(LogServiceTags.SAVE).performClick()
        compose.onNodeWithText("Needed for km-based tasks").assertIsDisplayed()

        compose.onNodeWithTag(LogServiceTags.task(cvtId)).performClick()
        compose.onNodeWithTag(LogServiceTags.ODOMETER).performTextInput("11,200")
        compose.onNodeWithTag(LogServiceTags.COST).performTextInput("1,850")
        compose.onNodeWithTag(LogServiceTags.SHOP).performTextInput("Motoworks")
        compose.onNodeWithTag(LogServiceTags.SAVE).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedCount != null }
        assertEquals(2, savedCount)
        val (forAsset, visit) = maintenance.visits.single()
        assertEquals(assetId, forAsset)
        assertEquals(setOf(oilId, cvtId), visit.scheduleIds.toSet())
        assertEquals(11_200, visit.odometerKm)
        assertEquals(Peso(185_000), visit.cost)
        assertEquals("Motoworks", visit.shop)
        assertEquals(clock.instant(), visit.performedAt)
    }

    @Test fun adHocRepairNeedsADescription() {
        launch(preselect = false)
        compose.onNodeWithTag(LogServiceTags.SAVE).performClick()
        compose.onNodeWithText("Tick a task or describe the repair").assertIsDisplayed()
        assertTrue(maintenance.visits.isEmpty())

        compose.onNodeWithTag(LogServiceTags.NOTES).performTextInput("Replaced side mirror")
        compose.onNodeWithTag(LogServiceTags.SAVE).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedCount != null }
        val visit = maintenance.visits.single().second
        assertTrue(visit.scheduleIds.isEmpty())
        assertEquals("Replaced side mirror", visit.notes)
    }
}
