package com.kronokeep.app.ui.assets.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.data.document.CaptureFiles
import com.kronokeep.app.domain.asset.DeleteAssetUseCase
import com.kronokeep.app.domain.asset.SetAssetArchivedUseCase
import com.kronokeep.app.domain.document.AddDocumentResult
import com.kronokeep.app.domain.document.AddDocumentUseCase
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.DocumentRepository
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.label
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AssetDetailUiState {
    data object Loading : AssetDetailUiState
    data object Missing : AssetDetailUiState
    data class Loaded(
        val asset: Asset,
        val documents: List<Document>,
        val isImporting: Boolean,
    ) : AssetDetailUiState
}

data class AssetDetailTransient(
    val isImporting: Boolean = false,
    val message: String? = null,
    /** One-shot: the asset was deleted from this screen, so close it. */
    val closed: Boolean = false,
)

@HiltViewModel(assistedFactory = AssetDetailViewModel.Factory::class)
class AssetDetailViewModel @AssistedInject constructor(
    @Assisted private val assetId: String,
    assets: AssetRepository,
    private val documents: DocumentRepository,
    private val setArchived: SetAssetArchivedUseCase,
    private val deleteAsset: DeleteAssetUseCase,
    private val addDocumentUseCase: AddDocumentUseCase,
    private val captures: CaptureFiles,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(assetId: String): AssetDetailViewModel
    }

    private val _transient = MutableStateFlow(AssetDetailTransient())
    val transient: StateFlow<AssetDetailTransient> = _transient

    val state: StateFlow<AssetDetailUiState> = combine(
        assets.observe(assetId),
        documents.observeForAsset(assetId),
        _transient,
    ) { asset, docs, t ->
        if (asset == null) AssetDetailUiState.Missing else AssetDetailUiState.Loaded(asset, docs, t.isImporting)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState.Loading)

    fun toggleArchived() {
        val asset = (state.value as? AssetDetailUiState.Loaded)?.asset ?: return
        viewModelScope.launch {
            setArchived(assetId, !asset.isArchived)
            showMessage(if (asset.isArchived) "Moved back to your assets" else "Archived")
        }
    }

    fun delete() {
        viewModelScope.launch {
            if (deleteAsset(assetId)) _transient.update { it.copy(closed = true) }
        }
    }

    fun newCaptureUri(): String = captures.newCaptureUri()

    /** Camera returned without a photo: drop the empty capture file. */
    fun captureCancelled(uri: String) = captures.delete(uri)

    fun addDocument(uri: String, mimeType: String?, kind: DocumentKind, isCapture: Boolean = false) {
        _transient.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            val result = try {
                addDocumentUseCase(assetId, kind, uri, mimeType)
            } catch (e: Exception) {
                AddDocumentResult.Failed(e.message ?: "Couldn't save the file")
            } finally {
                if (isCapture) captures.delete(uri)
            }
            _transient.update { it.copy(isImporting = false) }
            showMessage(
                when (result) {
                    is AddDocumentResult.Added -> "${kindLabel(kind)} saved"
                    AddDocumentResult.UnsupportedType -> "Only JPEG, PNG, HEIC and PDF files are supported"
                    is AddDocumentResult.TooLarge -> "That file is ${Formats.bytes(result.sizeBytes)}. The limit is 15 MB."
                    is AddDocumentResult.Failed -> result.message
                    AddDocumentResult.AssetNotFound -> "This asset no longer exists"
                },
            )
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            if (documents.delete(id)) showMessage("Document removed")
        }
    }

    fun onMessageShown() = _transient.update { it.copy(message = null) }
    fun onClosedHandled() = _transient.update { it.copy(closed = false) }

    fun showMessage(text: String) = _transient.update { it.copy(message = text) }

    private fun kindLabel(kind: DocumentKind) = kind.label
}
