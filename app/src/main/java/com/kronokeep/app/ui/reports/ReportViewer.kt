package com.kronokeep.app.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.data.document.ViewerFiles
import com.kronokeep.app.data.remote.SignedUrlDownloader
import com.kronokeep.app.domain.repository.ReportFailure
import com.kronokeep.app.domain.repository.ReportRepository
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.documents.PdfPages
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

sealed interface ReportViewerUiState {
    data object Loading : ReportViewerUiState
    data class Pdf(val path: String) : ReportViewerUiState
    data class Error(val message: String) : ReportViewerUiState
}

/** Fetches the PDF through a ≤10 min signed URL into a cache file that is deleted when the viewer closes. */
@HiltViewModel(assistedFactory = ReportViewerViewModel.Factory::class)
class ReportViewerViewModel @AssistedInject constructor(
    @Assisted("assetId") private val assetId: String,
    @Assisted("reportId") private val reportId: String,
    private val reports: ReportRepository,
    private val downloader: SignedUrlDownloader,
    private val viewerFiles: ViewerFiles,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("assetId") assetId: String, @Assisted("reportId") reportId: String): ReportViewerViewModel
    }

    private val _state = MutableStateFlow<ReportViewerUiState>(ReportViewerUiState.Loading)
    val state: StateFlow<ReportViewerUiState> = _state.asStateFlow()
    private var tempFile: File? = null

    init {
        load()
    }

    fun load() {
        _state.value = ReportViewerUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val report = reports.list(assetId).firstOrNull { it.id == reportId } ?: throw ReportFailure.NotFound()
                val bytes = downloader.download(reports.pdfUrl(report))
                val file = viewerFiles.writeTemp("report-$reportId.pdf", bytes)
                tempFile?.takeIf { it != file }?.let(viewerFiles::delete)
                tempFile = file
                ReportViewerUiState.Pdf(file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ReportFailure.NotFound) {
                ReportViewerUiState.Error("This report isn't on the server any more.")
            } catch (_: Exception) {
                ReportViewerUiState.Error("Couldn't download the report. Check your connection.")
            }
        }
    }

    override fun onCleared() {
        tempFile?.let(viewerFiles::delete)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportViewerScreen(viewModel: ReportViewerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verified report") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ReportViewerUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReportViewerUiState.Error -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Can't open this report",
                    body = s.message,
                    action = { Button(onClick = viewModel::load) { Text("Try again") } },
                )
                is ReportViewerUiState.Pdf -> PdfPages(s.path)
            }
        }
    }
}
