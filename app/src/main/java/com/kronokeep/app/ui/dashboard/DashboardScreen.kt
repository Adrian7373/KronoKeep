package com.kronokeep.app.ui.dashboard

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
import androidx.compose.material.icons.outlined.Upcoming
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.dashboard.DashboardSummary
import com.kronokeep.app.domain.dashboard.DueItem
import com.kronokeep.app.domain.dashboard.FeaturedAsset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.ui.assets.icon
import com.kronokeep.app.ui.assets.label
import com.kronokeep.app.ui.components.CloudNotConfiguredBanner
import com.kronokeep.app.ui.components.EmptyState
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.IconBadge
import com.kronokeep.app.ui.components.KronoCard
import com.kronokeep.app.ui.components.KronoTopBar
import com.kronokeep.app.ui.components.SectionLabel
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.maintenance.StatusPill
import com.kronokeep.app.ui.settings.ThemeToggleButton
import com.kronokeep.app.ui.theme.KronoTheme
import com.kronokeep.app.ui.theme.SerialTextStyle
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    cloudConfigured: Boolean,
    onOpenAsset: (assetId: String) -> Unit,
    onAddAsset: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val summary = (state as? DashboardUiState.Ready)?.summary
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            KronoTopBar(
                title = "Your vault",
                subtitle = summary?.let(::subtitle),
                actions = { ThemeToggleButton() },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
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
                        EmptyState(
                            icon = Icons.Outlined.EventAvailable,
                            title = "Nothing due yet",
                            body = "Add a vehicle or appliance and KronoKeep will tell you when maintenance is coming up.",
                            action = { Button(onClick = onAddAsset) { Text("Add your first asset") } },
                            modifier = Modifier.padding(top = 48.dp),
                        )
                    }
                } else {
                    dashboard(s.summary, s.now, onOpenAsset)
                }
            }
        }
    }
}

private fun subtitle(summary: DashboardSummary): String = when {
    summary.assetCount == 0 -> "Start by adding an asset"
    summary.overdue.isNotEmpty() -> "${MaintenanceFormats.plural(summary.overdue.size, "task")} need you now"
    summary.dueSoon.isNotEmpty() -> "${MaintenanceFormats.plural(summary.dueSoon.size, "task")} coming up soon"
    else -> "All clear. Nothing due in the next two weeks."
}

private fun LazyListScope.dashboard(summary: DashboardSummary, now: Instant, onOpenAsset: (String) -> Unit) {
    summary.featured?.let { f -> item(key = "hero") { FeaturedHero(f, now, onOpen = { onOpenAsset(f.asset.id) }) } }
    dueSection("overdue", "Overdue", Icons.Outlined.ReportProblem, summary.overdue, now, onOpenAsset)
    dueSection("due-soon", "Due soon", Icons.Outlined.Schedule, summary.dueSoon, now, onOpenAsset)
    dueSection("upcoming", "Coming up", Icons.Outlined.Upcoming, summary.upcoming, now, onOpenAsset)

    if (summary.staleOdometers.isNotEmpty()) {
        item(key = "odo-header") { SectionLabel("Update the odometer", icon = Icons.Outlined.Speed) }
        items(summary.staleOdometers, key = { "odo-${it.asset.id}" }) { stale ->
            DashboardRow(
                icon = stale.asset.category.icon,
                overline = null,
                title = stale.asset.name,
                body = stale.daysSince?.let { "Last reading $it days ago. Km-based due dates are estimates until you update." }
                    ?: "No reading yet. Add one so km-based tasks can be predicted.",
                onClick = { onOpenAsset(stale.asset.id) },
            )
        }
    }

    if (summary.warranties.isNotEmpty()) {
        item(key = "warranty-header") { SectionLabel("Warranties", icon = Icons.Outlined.Shield) }
        items(summary.warranties, key = { "w-${it.asset.id}" }) { w ->
            DashboardRow(
                icon = w.asset.category.icon,
                overline = null,
                title = w.asset.name,
                body = Formats.warranty(w.expiresOn, w.expiresOn.minusDays(w.daysLeft)),
                bodyColor = if (w.daysLeft < 0) KronoTheme.status.overdue else KronoTheme.status.dueSoon,
                onClick = { onOpenAsset(w.asset.id) },
            )
        }
    }
}

private fun LazyListScope.dueSection(
    key: String,
    title: String,
    icon: ImageVector,
    items: List<DueItem>,
    now: Instant,
    onOpenAsset: (String) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "$key-header") {
        SectionLabel(
            title,
            icon = icon,
            color = if (key == "overdue") KronoTheme.status.overdue else MaterialTheme.colorScheme.primary,
            trailing = { Text("${items.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    items(items, key = { "$key-${it.item.schedule.id}" }) { due ->
        val p = due.item.prediction
        DashboardRow(
            icon = due.asset.category.icon,
            overline = due.asset.name,
            title = due.item.schedule.task,
            body = listOf(MaintenanceFormats.due(p, now), MaintenanceFormats.dueDetail(p)).filter { it.isNotEmpty() }.joinToString("\n"),
            status = p.status,
            onClick = { onOpenAsset(due.asset.id) },
        )
    }
}

@Composable
private fun DashboardRow(
    icon: ImageVector,
    overline: String?,
    title: String,
    body: String,
    onClick: () -> Unit,
    status: MaintenanceStatus? = null,
    bodyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val s = KronoTheme.status
    val (badgeBg, badgeFg) = when (status) {
        MaintenanceStatus.OVERDUE -> s.overdueContainer to s.onOverdueContainer
        MaintenanceStatus.DUE_SOON -> s.dueSoonContainer to s.onDueSoonContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    KronoCard(onClick = onClick, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, container = badgeBg, content = badgeFg)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                overline?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    status?.let { StatusPill(it) }
                }
                Text(body, style = MaterialTheme.typography.bodySmall, color = bodyColor)
            }
        }
    }
}

/**
 * The vault card from the design: a deep-teal panel featuring the asset the user works with most. Vehicles lead
 * with the odometer, daily use and last reading; everything else with brand/model, warranty and last service.
 */
@Composable
private fun FeaturedHero(f: FeaturedAsset, now: Instant, onOpen: () -> Unit) {
    val hero = KronoTheme.hero
    val asset = f.asset
    val vehicle = asset.category == AssetCategory.VEHICLE
    Surface(
        onClick = onOpen,
        color = hero.container,
        contentColor = hero.content,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            Modifier
                .drawBehind {
                    // Two concentric rings off the top-right corner, like a stamped seal.
                    val c = Offset(size.width - 36.dp.toPx(), 28.dp.toPx())
                    drawCircle(hero.ring, radius = 92.dp.toPx(), center = c, style = Stroke(22.dp.toPx()))
                    drawCircle(hero.ring, radius = 40.dp.toPx(), center = c)
                }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(asset.category.icon, contentDescription = null, tint = hero.muted, modifier = Modifier.size(16.dp))
                Text("Most active · ${asset.category.label}", style = MaterialTheme.typography.labelLarge, color = hero.muted)
            }
            Text(
                asset.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, end = 56.dp).semantics { heading() },
            )
            val subline = listOfNotNull(
                listOfNotNull(asset.brand, asset.model).joinToString(" ").takeIf { it.isNotEmpty() },
                asset.serialNo?.let { "SN $it" },
            ).joinToString(" · ")
            if (subline.isNotEmpty()) {
                Text(
                    subline,
                    style = SerialTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = hero.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (vehicle) {
                Text("Odometer", style = MaterialTheme.typography.labelLarge, color = hero.muted, modifier = Modifier.padding(top = 16.dp))
                Text(
                    f.currentKm?.let(Formats::km) ?: "No reading yet",
                    style = if (f.currentKm != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                )
            }

            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (vehicle) {
                    HeroFigure(f.rate?.kmPerDay?.let { "~${it.roundToInt()}" } ?: "–", "km per day")
                    HeroFigure(f.lastReadingAt?.let { MaintenanceFormats.ago(it, now) } ?: "–", "last reading")
                } else {
                    HeroFigure(f.lastServiceAt?.let { MaintenanceFormats.ago(it, now) } ?: "–", "last service")
                    asset.warrantyExpiresOn?.let {
                        val ended = it.isBefore(now.atZone(ZoneId.systemDefault()).toLocalDate())
                        HeroFigure(Formats.date(it), if (ended) "warranty ended" else "warranty until", hero.accent)
                    }
                }
                HeroFigure(
                    "${f.attentionCount}",
                    if (f.attentionCount == 1) "task due" else "tasks due",
                    if (f.attentionCount == 0) hero.content else hero.dueSoon,
                )
            }

            f.nextTask?.let { next ->
                val p = next.prediction
                val label = MaintenanceFormats.tagLabel(next.schedule.task, p, now) ?: next.schedule.task
                Text(
                    "Next: $label",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (p.status) {
                        MaintenanceStatus.OVERDUE -> hero.overdue
                        MaintenanceStatus.DUE_SOON -> hero.dueSoon
                        MaintenanceStatus.OK -> hero.content
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroFigure(value: String, label: String, color: Color = KronoTheme.hero.content) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = KronoTheme.hero.muted)
    }
}
