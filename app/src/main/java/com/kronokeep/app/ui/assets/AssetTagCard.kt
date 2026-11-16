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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.dashboard.AssetTag
import com.kronokeep.app.domain.dashboard.TagHighlight
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.components.SyncBadge
import com.kronokeep.app.ui.maintenance.MaintenanceFormats
import com.kronokeep.app.ui.maintenance.StatusPill
import com.kronokeep.app.ui.theme.KronoTheme
import com.kronokeep.app.ui.theme.MetalColors
import com.kronokeep.app.ui.theme.SerialTextStyle
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

object AssetTagTags {
    fun card(id: String) = "asset_tag_$id"
}

/**
 * An asset drawn as a riveted metal asset tag: name and the one thing that needs attention, the serial stamped
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
                .drawBehind { drawRivets(metal) }
                .padding(start = 40.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
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

/** Brushed-metal face: four stops along a ~115 degree diagonal, like the CSS preview. */
private fun brushedMetal(stops: List<Color>): Brush {
    val angle = Math.toRadians(115.0 - 90.0)
    val dx = cos(angle).toFloat()
    val dy = sin(angle).toFloat()
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val half = (size.width * dx + size.height * dy) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            return LinearGradientShader(
                from = Offset(c.x - dx * half, c.y - dy * half),
                to = Offset(c.x + dx * half, c.y + dy * half),
                colors = stops,
                colorStops = listOf(0f, 0.38f, 0.55f, 1f),
            )
        }
    }
}

/** Two rivets on the tag's left edge, lit from the top left. */
private fun DrawScope.drawRivets(metal: MetalColors) {
    val r = 6.dp.toPx()
    val x = 14.dp.toPx() + r
    val inset = 16.dp.toPx() + r
    listOf(inset, size.height - inset).forEach { y ->
        val center = Offset(x, y)
        drawCircle(
            Brush.radialGradient(
                0f to metal.rivetHighlight,
                0.2f to metal.rivetHighlight,
                0.6f to metal.rivetMid,
                1f to metal.rivetShadow,
                center = Offset(center.x - r * 0.3f, center.y - r * 0.3f),
                radius = r * 1.3f,
            ),
            radius = r,
            center = center,
        )
    }
}
