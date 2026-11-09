package com.kronokeep.app.ui.assets.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kronokeep.app.domain.asset.CreateAssetUseCase
import com.kronokeep.app.domain.asset.UpdateAssetUseCase
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.ui.theme.KronoKeepTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class AddAssetFlowTest {

    @get:Rule val compose = createComposeRule()

    private val repo = InMemoryAssetRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-14T02:00:00Z"), ZoneOffset.UTC)
    private var savedId: String? = null

    private fun launch() {
        val vm = AssetEditorViewModel(
            assetId = null,
            createAsset = CreateAssetUseCase(repo, clock),
            updateAsset = UpdateAssetUseCase(repo, clock),
            repository = repo,
            clock = clock,
        )
        compose.setContent {
            KronoKeepTheme {
                AssetEditorScreen(viewModel = vm, onSaved = { savedId = it }, onClose = {})
            }
        }
    }

    @Test fun vehicleWizardSavesDetailsAndBaselines() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.VEHICLE)).performClick()
        compose.onNodeWithTag(AssetEditorTags.NAME).performTextInput("Honda Click 125i")
        compose.onNodeWithTag(AssetEditorTags.PRICE).performTextInput("89,900")
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.onNodeWithText("Step 3 of 3").assertIsDisplayed()
        compose.onNodeWithTag(AssetEditorTags.ODOMETER).performTextInput("12400")
        compose.onNodeWithTag(AssetEditorTags.WEEKLY_KM).performTextInput("150")
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedId != null }
        val draft = repo.drafts.getValue(savedId!!)
        assertEquals(AssetCategory.VEHICLE, draft.category)
        assertEquals("Honda Click 125i", draft.name)
        assertEquals(Peso(8_990_000), draft.price)
        assertEquals(12_400, draft.currentOdometerKm)
        assertEquals(150, draft.weeklyKmBaseline)
    }

    @Test fun missingNameBlocksTheDetailsStep() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.VEHICLE)).performClick()
        compose.onNodeWithTag(AssetEditorTags.NEXT).performClick()

        compose.onNodeWithText("Required").assertIsDisplayed()
        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        assertTrue(repo.drafts.isEmpty())
        assertNull(savedId)
    }

    @Test fun applianceSkipsBaselinesAndSavesFromDetails() {
        launch()
        compose.onNodeWithTag(AssetEditorTags.category(AssetCategory.APPLIANCE)).performClick()
        compose.onNodeWithText("Step 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag(AssetEditorTags.NAME).performTextInput("Bedroom aircon")
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { savedId != null }
        val draft = repo.drafts.getValue(savedId!!)
        assertEquals(AssetCategory.APPLIANCE, draft.category)
        assertNull(draft.currentOdometerKm)
    }
}

private class InMemoryAssetRepository : AssetRepository {
    val drafts = mutableMapOf<String, AssetDraft>()
    private val flow = MutableStateFlow<Map<String, Asset>>(emptyMap())

    private fun AssetDraft.toAsset(id: String) = Asset(
        id = id, category = category, name = name, brand = brand, model = model, serialNo = serialNo,
        purchaseDate = purchaseDate, price = price, warrantyExpiresOn = warrantyExpiresOn,
        currentOdometerKm = currentOdometerKm, weeklyKmBaseline = weeklyKmBaseline, notes = notes,
        archivedAt = null, updatedAt = Instant.EPOCH, syncState = SyncState.PENDING,
    )

    override fun observeActive(): Flow<List<Asset>> = flow.map { it.values.toList() }
    override fun observeArchived(): Flow<List<Asset>> = flow.map { emptyList() }
    override fun observe(id: String): Flow<Asset?> = flow.map { it[id] }
    override suspend fun get(id: String): Asset? = flow.value[id]

    override suspend fun create(draft: AssetDraft): String {
        val id = "asset-${drafts.size + 1}"
        drafts[id] = draft
        flow.value = flow.value + (id to draft.toAsset(id))
        return id
    }

    override suspend fun update(id: String, draft: AssetDraft): Boolean {
        if (id !in drafts) return false
        drafts[id] = draft
        flow.value = flow.value + (id to draft.toAsset(id))
        return true
    }

    override suspend fun setArchived(id: String, archived: Boolean) = id in drafts
    override suspend fun delete(id: String) = drafts.remove(id) != null
}
