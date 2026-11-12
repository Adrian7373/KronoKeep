package com.kronokeep.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.domain.report.GenerateReportResult
import com.kronokeep.app.domain.report.GenerateReportUseCase
import com.kronokeep.app.domain.report.ReportShareText
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.MaintenanceRepository
import com.kronokeep.app.domain.repository.ReportFailure
import com.kronokeep.app.domain.repository.ReportRepository
import com.kronokeep.app.ui.components.Formats
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

data class ReportsUiState(
    val assetName: String? = null,
    /** Live service entries on the phone; the report needs at least one. */
    val localLogCount: Int = 0,
    val cloudConfigured: Boolean = true,
    val reports: List<Report> = emptyList(),
    val loading: Boolean = true,
    val loadError: String? = null,
    val generating: Boolean = false,
    /** A share or revoke in flight for this report. */
    val busyReportId: String? = null,
    val now: Instant = Instant.EPOCH,
)

private data class Remote(
    val reports: List<Report> = emptyList(),
    val loading: Boolean = true,
    val loadError: String? = null,
    val generating: Boolean = false,
    val busyReportId: String? = null,
)

@HiltViewModel(assistedFactory = ReportsViewModel.Factory::class)
class ReportsViewModel @AssistedInject constructor(
    @Assisted private val assetId: String,
    assets: AssetRepository,
    maintenance: MaintenanceRepository,
    private val auth: AuthRepository,
    private val reports: ReportRepository,
    private val generateReport: GenerateReportUseCase,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(assetId: String): ReportsViewModel
    }

    private val remote = MutableStateFlow(Remote())

    /** One-shot snackbar text. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** One-shot: text for the Android share sheet. */
    private val _shareText = MutableStateFlow<String?>(null)
    val shareText: StateFlow<String?> = _shareText

    val state: StateFlow<ReportsUiState> = combine(
        assets.observe(assetId),
        maintenance.observeServiceLogs(assetId),
        auth.state,
        remote,
    ) { asset, logs, authState, r ->
        ReportsUiState(
            assetName = asset?.name,
            localLogCount = logs.size,
            cloudConfigured = authState != AuthState.Unconfigured,
            reports = r.reports,
            loading = r.loading,
            loadError = r.loadError,
            generating = r.generating,
            busyReportId = r.busyReportId,
            now = clock.instant(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (auth.state.value == AuthState.Unconfigured) {
            remote.update { it.copy(loading = false, loadError = null) }
            return
        }
        remote.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val list = reports.list(assetId)
                remote.update { it.copy(reports = list, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReportFailure) {
                remote.update { it.copy(loading = false, loadError = e.listMessage()) }
            }
        }
    }

    fun generate() {
        if (remote.value.generating) return
        remote.update { it.copy(generating = true) }
        viewModelScope.launch {
            val result = generateReport(assetId)
            remote.update { it.copy(generating = false) }
            _message.value = ReportFormats.message(result)
            if (result is GenerateReportResult.Generated) refresh()
        }
    }

    fun share(report: Report) = busy(report) {
        val link = reports.share(report.id)
        _shareText.value = ReportShareText.build(
            assetName = state.value.assetName ?: "this asset",
            url = link.url,
            expiresOn = Formats.date(link.expiresAt),
        )
        refreshQuietly()
    }

    fun revoke(report: Report) = busy(report) {
        reports.revoke(report.id)
        _message.value = "Report revoked. Scanning its QR code now shows Revoked, and its link no longer works."
        refreshQuietly()
    }

    private fun busy(report: Report, block: suspend () -> Unit) {
        if (remote.value.busyReportId != null) return
        remote.update { it.copy(busyReportId = report.id) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReportFailure) {
                _message.value = when (e) {
                    is ReportFailure.Revoked -> "This report was revoked, so it can't be shared."
                    is ReportFailure.NotSignedIn -> "Sign in again to manage reports."
                    else -> "Couldn't reach the server. Check your connection."
                }
            } finally {
                remote.update { it.copy(busyReportId = null) }
            }
        }
    }

    private suspend fun refreshQuietly() {
        runCatching { reports.list(assetId) }.onSuccess { list -> remote.update { it.copy(reports = list) } }
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onShareLaunched() {
        _shareText.value = null
    }

    private fun ReportFailure.listMessage(): String = when (this) {
        is ReportFailure.NotSignedIn -> "Sign in to see your reports."
        is ReportFailure.CloudNotConfigured -> "Cloud sync isn't set up in this build."
        else -> "Couldn't load reports. Check your connection."
    }
}
