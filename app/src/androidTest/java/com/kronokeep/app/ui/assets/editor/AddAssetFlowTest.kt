package com.kronokeep.app.ui.assets.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kronokeep.app.data.document.CaptureFiles
import com.kronokeep.app.domain.capture.OcrLine
import com.kronokeep.app.domain.capture.ReceiptTextReader
import com.kronokeep.app.domain.capture.ScanReceiptUseCase
import com.kronokeep.app.domain.common.IdGenerator
import com.kronokeep.app.domain.document.AddDocumentUseCase
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.asset.CreateAssetUseCase
import com.kronokeep.app.domain.asset.UpdateAssetUseCase
import com.kronokeep.app.domain.maintenance.AddSchedulesUseCase
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.presets.Preset
import com.kronokeep.app.testing.FakeDocumentImporter
import com.kronokeep.app.testing.InMemoryAssetRepository
import com.kronokeep.app.testing.InMemoryDocumentRepository
import com.kronokeep.app.testing.InMemoryMaintenanceRepository
import com.kronokeep.app.ui.theme.KronoKeepTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class AddAssetFlowTest {

    @get:Rule val compose = createComposeRule()

    private val repo = InMemoryAssetRepository()
    private val maintenance = InMemoryMaintenanceRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-14T02:00:00Z"), ZoneOffset.UTC)
    private val documents = InMemoryDocumentRepository()
    private val importer = FakeDocumentImporter()
    private var receiptLines: List<OcrLine> = emptyList()
    private var savedId: String? = null

    private fun launch(): AssetEditorViewModel {
        val vm = AssetEditorViewModel(
            assetId = null,
            createAsset = CreateAssetUseCase(repo, clock),
            updateAsset = UpdateAssetUseCase(repo, clock),
            addSchedules = AddSchedulesUseCase(repo, maintenance, clock),
            scanReceipt = ScanReceiptUseCase(ReceiptTextReader { receiptLines }, clock, ZoneOffset.UTC),
            addDocument = AddDocumentUseCase(repo, documents, importer, IdGenerator.Random),
            captures = CaptureFiles(InstrumentationRegistry.getInstrumentation().targetContext),
            repository = repo,
            clock = clock,
            zone = ZoneOffset.UTC,
        )
        compose.setContent {
            KronoKeepTheme {
                AssetEditorScreen(viewModel = vm, onSaved = { savedId = it }, onClose = {})
            }
        }
        return vm
    }

    @Test fun receiptScanFillsDetailsAndIsAttachedOnSave() {
        // OCR output with the amount in its own column, as ML Kit returns it.
        receiptLines = listOf(
            OcrLine("ABENSON APPLIANCE", 0, 0, 400, 20),
            OcrLine("Date: 11/09/2026", 0, 40, 300, 60),
            OcrLine("S/N: KX2A9087B", 0, 80, 300, 100),
            OcrLine("TOTAL AMOUNT DUE", 0, 120, 300, 140),
            OcrLine("35,999.00", 400, 121, 500, 141),
        )
        val vm = launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.ELECTRONIC)).performClick()
        compose.onNodeWithTag(AssetEditorTags.NAME).performTextInput("Inverter aircon")
        // The system camera can't be driven from a test, so hand the picked photo straight to the ViewModel.
        compose.runOnIdle { vm.onReceiptPicked("content://test/receipt.jpg", isCapture = false) }

        compose.onNodeWithText("Found on the receipt").assertIsDisplayed()
        compose.onNodeWithTag(ReceiptTags.APPLY).performClick()
        compose.onNodeWithText("Filled in from the receipt").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedId != null && documents.added.isNotEmpty() }
        val draft = repo.drafts.getValue(savedId!!)
        assertEquals(LocalDate.of(2026, 11, 9), draft.purchaseDate)
        assertEquals(Peso(3_599_900), draft.price)
        assertEquals("KX2A9087B", draft.serialNo)
        assertEquals("Bought at Abenson Appliance", draft.notes)
        assertEquals(DocumentKind.RECEIPT, documents.added.single().kind)
        assertEquals(savedId, documents.added.single().assetId)
        assertEquals(listOf("content://test/receipt.jpg"), importer.imported)
    }

    @Test fun vehicleWizardSavesDetailsBaselinesAndSuggestedSchedules() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.VEHICLE)).performClick()
        compose.onNodeWithTag(AssetEditorTags.NAME).performTextInput("Honda Click 125i")
        compose.onNodeWithTag(AssetEditorTags.PRICE).performTextInput("89,900")
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.onNodeWithText("Step 3 of 4").assertIsDisplayed()
        compose.onNodeWithTag(AssetEditorTags.ODOMETER).performTextInput("12400")
        compose.onNodeWithTag(AssetEditorTags.WEEKLY_KM).performTextInput("150")
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        // "Click" in the name picks the scooter preset, all four tasks ticked.
        compose.onNodeWithText("Step 4 of 4").assertIsDisplayed()
        compose.onNodeWithTag(ScheduleStepTags.preset(Preset.SCOOTER)).assertIsSelected()
        compose.onNodeWithText("CVT cleaning").assertIsDisplayed()
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedId != null }
        val draft = repo.drafts.getValue(savedId!!)
        assertEquals(AssetCategory.VEHICLE, draft.category)
        assertEquals("Honda Click 125i", draft.name)
        assertEquals(Peso(8_990_000), draft.price)
        assertEquals(12_400, draft.currentOdometerKm)
        assertEquals(150, draft.weeklyKmBaseline)

        compose.waitUntil(timeoutMillis = 5_000) { maintenance.added.size == 4 }
        assertTrue(maintenance.added.all { (assetId, d) -> assetId == savedId && d.lastDoneKm == 12_400 && d.lastDoneAt == clock.instant() })
        assertEquals(1_500, maintenance.added.first { it.second.task == "Engine oil change" }.second.intervalKm)
    }

    @Test fun missingNameBlocksTheDetailsStep() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.VEHICLE)).performClick()
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.onNodeWithText("Required").assertIsDisplayed()
        compose.onNodeWithText("Step 2 of 4").assertIsDisplayed()
        assertTrue(repo.drafts.isEmpty())
        assertNull(savedId)
    }

    @Test fun applianceSkipsBaselinesAndCanSkipSchedules() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.APPLIANCE)).performClick()
        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        compose.onNodeWithTag(AssetEditorTags.NAME).performTextInput("Bedroom aircon")
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.onNodeWithTag(ScheduleStepTags.preset(Preset.AIRCON)).assertIsSelected()
        compose.onNodeWithTag(ScheduleStepTags.NO_PRESET).performClick()
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedId != null }
        val draft = repo.drafts.getValue(savedId!!)
        assertEquals(AssetCategory.APPLIANCE, draft.category)
        assertNull(draft.currentOdometerKm)
        assertTrue(maintenance.added.isEmpty())
    }

    @Test fun electronicsFinishAfterDetails() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.ELECTRONIC)).performClick()
        compose.onNodeWithText("Step 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag(AssetEditorTags.NAME).performTextInput("Work laptop")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { savedId != null }
    }
}
