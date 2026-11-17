package com.kronokeep.app.ui.reports

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.IconBadge
import com.kronokeep.app.ui.components.KronoCard
import com.kronokeep.app.ui.components.KronoDefaults
import com.kronokeep.app.ui.components.KronoTopBar
import com.kronokeep.app.ui.theme.KronoTheme
import com.kronokeep.app.ui.theme.SerialTextStyle
import java.time.Instant

object ReportsTags {
    const val GENERATE = "reports_generate"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onBack: () -> Unit,
    onOpenReport: (reportId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val shareText by viewModel.shareText.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmRevoke by remember { mutableStateOf<Report?>(null) }

    LaunchedEffect(message) {
        message?.let {
            viewModel.onMessageShown()
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(shareText) {
        shareText?.let { text ->
            viewModel.onShareLaunched()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Verified maintenance record")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Share report link"))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            KronoTopBar(
                title = "Verified report",
                subtitle = "For the buyer, checkable by QR",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (!state.cloudConfigured) {
            EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = "Cloud not configured",
                body = "Verified reports are generated and signed on the server, so they need cloud sync.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                IntroCard(state, onGenerate = viewModel::generate)
            }
            when {
                state.loading && state.reports.isEmpty() -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.loadError != null && state.reports.isEmpty() -> item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.loadError!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                state.reports.isEmpty() -> item(key = "none") {
                    Text(
                        "No reports yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                else -> {
                    item(key = "header") {
                        Text("Your reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(state.reports, key = { it.id }) { report ->
                        ReportCard(
                            report = report,
                            now = state.now,
                            busy = state.busyReportId == report.id,
                            onView = { onOpenReport(report.id) },
                            onShare = { viewModel.share(report) },
                            onRevoke = { confirmRevoke = report },
                        )
                    }
                }
            }
        }
    }

    confirmRevoke?.let { report ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            icon = { Icon(Icons.Outlined.Block, contentDescription = null) },
            title = { Text("Revoke this report?") },
            text = {
                Text(
                    "Anyone who scans its QR code will see Revoked, and the share link stops working right away. " +
                        "The service entries it covers stay locked. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = null
                    viewModel.revoke(report)
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IntroCard(state: ReportsUiState, onGenerate: () -> Unit) {
    KronoCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    Icons.Outlined.VerifiedUser,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text("Hand over a record buyers can check", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "The server builds a PDF from your synced service history, stamps it with a SHA-256 fingerprint and " +
                    "a QR code anyone can scan to confirm it's genuine. Entries in a report are locked from then on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                ReportFormats.entries(state.localLogCount) + " on this phone",
                style = MaterialTheme.typography.labelLarge,
            )
            Button(
                onClick = onGenerate,
                enabled = !state.generating && state.localLogCount > 0,
                colors = KronoDefaults.recordButtonColors(),
                modifier = Modifier.fillMaxWidth().testTag(ReportsTags.GENERATE),
            ) {
                if (state.generating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating…")
                } else {
                    Text("Generate verified report")
                }
            }
        }
    }
}

@Composable
private fun ReportCard(
    report: Report,
    now: Instant,
    busy: Boolean,
    onView: () -> Unit,
    onShare: () -> Unit,
    onRevoke: () -> Unit,
) {
    KronoCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Formats.date(report.generatedAt), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ReportFormats.entries(report.logCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(report)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "sha-256 ${report.shortHash}…",
                    style = SerialTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Text(
                ReportFormats.shareLine(report, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onView) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View")
                }
                if (!report.isRevoked) {
                    Button(onClick = onShare, enabled = !busy, colors = KronoDefaults.recordButtonColors()) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (report.isShareActive(now)) "New link" else "Share")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRevoke, enabled = !busy) { Text("Revoke") }
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun StatusPill(report: Report) {
    val (container, content) = if (report.isRevoked) {
        KronoTheme.status.overdueContainer to KronoTheme.status.onOverdueContainer
    } else {
        KronoTheme.status.okContainer to KronoTheme.status.onOkContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (report.isRevoked) Icons.Outlined.Block else Icons.Outlined.Verified,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(ReportFormats.status(report), style = MaterialTheme.typography.labelMedium)
        }
    }
}
