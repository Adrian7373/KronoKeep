package com.kronokeep.app.ui.assets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.dashboard.AssetTag
import com.kronokeep.app.domain.dashboard.TagHighlight
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.SyncBadge
import com.kronokeep.app.ui.components.brushedMetal
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.maintenance.StatusPill
import com.kronokeep.app.ui.theme.KronoTheme
import com.kronokeep.app.ui.theme.SerialTextStyle
import java.time.Instant

object AssetTagTags {
    fun card(id: String) = "asset_tag_$id"
}

/**
 * An asset drawn as a brushed-metal asset tag: name and the one thing that needs attention, the serial stamped
 * in mono, then category, odometer and sync state. Mirrors design/previews/c-asset-tag.html.
 */
@Composable
fun AssetTagCard(tag: AssetTag, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val metal = KronoTheme.metal
    val shape = MaterialTheme.shapes.medium
    val asset = tag.asset
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, metal.edge),
        modifier = modifier.fillMaxWidth().testTag(AssetTagTags.card(asset.id)),
    ) {
        Row(
            Modifier
                .background(brushedMetal(metal.face))
                .padding(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        asset.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    tag.highlight?.let { Highlight(it, now) }
                }
                Text(
                    asset.serialNo?.let { "SN $it" } ?: "TAG KK-${asset.id.take(8).uppercase()}",
                    style = SerialTextStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = metal.serial,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        asset.category.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        tag.meta(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SyncBadge(asset.syncState)
                }
            }
        }
    }
}

@Composable
private fun Highlight(h: TagHighlight, now: Instant) {
    when (h) {
        is TagHighlight.Task -> {
            val p = h.item.prediction
            val base = MaintenanceFormats.tagLabel(h.item.schedule.task, p, now) ?: MaintenanceFormats.statusLabel(p.status)
            val label = if (h.othersNeedingAttention > 0) "$base +${h.othersNeedingAttention}" else base
            StatusPill(p.status, label = label, modifier = Modifier.widthCap())
        }
        is TagHighlight.Warranty -> if (h.daysLeft < 0) {
            PlainPill("Warranty ended", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val label = if (h.daysLeft == 0L) "Warranty ends today" else "Warranty ends in ${h.daysLeft} d"
            StatusPill(MaintenanceStatus.DUE_SOON, label = label, modifier = Modifier.widthCap())
        }
        // Brass marks things of record.
        is TagHighlight.UnderWarranty -> PlainPill(
            "Warranty to ${Formats.date(h.expiresOn)}",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PlainPill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = Modifier.widthCap()) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Keeps a long task name in the pill from squeezing the asset name to nothing. */
private fun Modifier.widthCap() = widthIn(max = 180.dp)

private fun AssetTag.meta(): String {
    val parts = mutableListOf(asset.category.label)
    if (asset.category == AssetCategory.VEHICLE) {
        currentKm?.let { parts += Formats.km(it) }
    } else {
        listOfNotNull(asset.brand, asset.model).joinToString(" ").takeIf { it.isNotEmpty() }?.let { parts += it }
    }
    return parts.joinToString(" · ")
}

