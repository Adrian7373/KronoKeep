package com.kronokeep.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.dashboard.DashboardSummary
import com.kronokeep.app.domain.dashboard.DueItem
import com.kronokeep.app.ui.assets.icon
import com.kronokeep.app.ui.components.CloudNotConfiguredBanner
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.maintenance.StatusPill
import com.kronokeep.app.ui.theme.KronoTheme
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    cloudConfigured: Boolean,
    onOpenAsset: (assetId: String) -> Unit,
    onAddAsset: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Dashboard") }) }) { padding ->
        when (val s = state) {
            DashboardUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is DashboardUiState.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (!cloudConfigured) item(key = "banner") { CloudNotConfiguredBanner() }
                if (s.summary.assetCount == 0) {
                    item(key = "empty") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState(
                                icon = Icons.Outlined.EventAvailable,
                                title = "Nothing due yet",
                                body = "Add a vehicle or appliance and KronoKeep will tell you when maintenance is coming up.",
                            )
                            Button(onClick = onAddAsset) { Text("Add your first asset") }
                        }
                    }
                } else {
                    dashboard(s.summary, s.now, onOpenAsset)
                }
            }
        }
    }
}

private fun LazyListScope.dashboard(summary: DashboardSummary, now: Instant, onOpenAsset: (String) -> Unit) {
    item(key = "headline") { Headline(summary) }
    dueSection("overdue", "Overdue", summary.overdue, now, onOpenAsset)
    dueSection("due-soon", "Due soon", summary.dueSoon, now, onOpenAsset)
    dueSection("upcoming", "Coming up", summary.upcoming, now, onOpenAsset)

    if (summary.staleOdometers.isNotEmpty()) {
        item(key = "odo-header") { SectionHeader("Update the odometer", Icons.Outlined.Speed) }
        items(summary.staleOdometers, key = { "odo-${it.asset.id}" }) { stale ->
            ListItem(
                headlineContent = { Text(stale.asset.name) },
                supportingContent = {
                    Text(
                        stale.daysSince?.let { "Last reading $it days ago. Km-based due dates are estimates until you update." }
                            ?: "No reading yet. Add one so km-based tasks can be predicted.",
                    )
                },
                leadingContent = { Icon(stale.asset.category.icon, contentDescription = null) },
                modifier = Modifier.clickable { onOpenAsset(stale.asset.id) },
            )
        }
    }

    if (summary.warranties.isNotEmpty()) {
        item(key = "warranty-header") { SectionHeader("Warranties", Icons.Outlined.Shield) }
        items(summary.warranties, key = { "w-${it.asset.id}" }) { w ->
            val color = if (w.daysLeft < 0) KronoTheme.status.overdue else KronoTheme.status.dueSoon
            ListItem(
                headlineContent = { Text(w.asset.name) },
                supportingContent = { Text(Formats.warranty(w.expiresOn, w.expiresOn.minusDays(w.daysLeft)), color = color) },
                leadingContent = { Icon(w.asset.category.icon, contentDescription = null) },
                modifier = Modifier.clickable { onOpenAsset(w.asset.id) },
            )
        }
    }
}

private fun LazyListScope.dueSection(key: String, title: String, items: List<DueItem>, now: Instant, onOpenAsset: (String) -> Unit) {
    if (items.isEmpty()) return
    item(key = "$key-header") {
        SectionHeader(title, if (key == "overdue") Icons.Outlined.ReportProblem else Icons.Outlined.Schedule)
    }
    items(items, key = { "$key-${it.item.schedule.id}" }) { due ->
        val p = due.item.prediction
        ListItem(
            overlineContent = { Text(due.asset.name) },
            headlineContent = { Text(due.item.schedule.task) },
            supportingContent = {
                Text(listOf(MaintenanceFormats.due(p, now), MaintenanceFormats.dueDetail(p)).filter { it.isNotEmpty() }.joinToString("\n"))
            },
            leadingContent = { Icon(due.asset.category.icon, contentDescription = null) },
            trailingContent = { StatusPill(p.status) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onOpenAsset(due.asset.id) },
        )
    }
}

@Composable
private fun Headline(summary: DashboardSummary) {
    val s = KronoTheme.status
    val (container, content, icon, text) = when {
        summary.overdue.isNotEmpty() -> Quad(s.overdueContainer, s.onOverdueContainer, Icons.Outlined.ReportProblem,
            "${MaintenanceFormats.plural(summary.overdue.size, "task")} overdue")
        summary.dueSoon.isNotEmpty() -> Quad(s.dueSoonContainer, s.onDueSoonContainer, Icons.Outlined.Schedule,
            "${MaintenanceFormats.plural(summary.dueSoon.size, "task")} due soon")
        else -> Quad(s.okContainer, s.onOkContainer, Icons.Outlined.TaskAlt, "All clear")
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${MaintenanceFormats.plural(summary.assetCount, "asset")} · ${MaintenanceFormats.plural(summary.scheduleCount, "schedule")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private data class Quad(val container: Color, val content: Color, val icon: ImageVector, val text: String)

@Composable
private fun SectionHeader(text: String, icon: ImageVector) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
}

