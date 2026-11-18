package com.kronokeep.app.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.data.document.ViewerFiles
import com.kronokeep.app.data.remote.CloudNotConfiguredException
import com.kronokeep.app.data.remote.SignedUrlDownloader
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.DocumentSource
import com.kronokeep.app.domain.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    /** [model] is a local [File] or in-memory bytes; never a URL, so nothing outlives the signed link. */
    data class Image(val kind: DocumentKind, val model: Any) : ViewerUiState
    data class Pdf(val kind: DocumentKind, val path: String) : ViewerUiState
    data class Error(val message: String) : ViewerUiState
}

@HiltViewModel(assistedFactory = DocumentViewerViewModel.Factory::class)
class DocumentViewerViewModel @AssistedInject constructor(
    @Assisted private val documentId: String,
    private val documents: DocumentRepository,
    private val downloader: SignedUrlDownloader,
    private val viewerFiles: ViewerFiles,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(documentId: String): DocumentViewerViewModel
    }

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** Downloaded PDFs only; deleted when the viewer closes. */
    private var tempFile: File? = null

    init {
        load()
    }

    fun load() {
        _state.value = ViewerUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val doc = documents.get(documentId) ?: throw NoSuchElementException("This document was removed")
                val isPdf = doc.mimeType == "application/pdf"
                when (val source = documents.source(documentId).getOrThrow()) {
                    is DocumentSource.Local ->
                        if (isPdf) ViewerUiState.Pdf(doc.kind, source.path) else ViewerUiState.Image(doc.kind, File(source.path))
                    is DocumentSource.SignedUrl -> {
                        // Fetch now while the ≤10 min link is fresh; the URL itself is never stored or cached.
                        val bytes = downloader.download(source.url)
                        if (isPdf) {
                            val file = viewerFiles.writeTemp("$documentId.pdf", bytes)
                            tempFile = file
                            ViewerUiState.Pdf(doc.kind, file.absolutePath)
                        } else {
                            ViewerUiState.Image(doc.kind, bytes)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: CloudNotConfiguredException) {
                ViewerUiState.Error("This file is in the cloud, but cloud sync isn't set up in this build.")
            } catch (_: Exception) {
                // Not e.message: storage errors carry the request URL and headers.
                ViewerUiState.Error("Couldn't open the document. Check your connection.")
            }
        }
    }

    override fun onCleared() {
        tempFile?.let(viewerFiles::delete)
    }
}
