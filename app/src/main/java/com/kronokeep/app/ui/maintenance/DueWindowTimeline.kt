package com.kronokeep.app.ui.maintenance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.maintenance.DueWindow
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.ui.theme.KronoTheme

/**
 * Due window timeline: an axis, the ± window as a band, the most likely day as a dot and today as a line.
 * The band's width is the engine's uncertainty, so the drawing never claims more precision than it has.
 */
@Composable
fun DueWindowTimeline(window: DueWindow, status: MaintenanceStatus, modifier: Modifier = Modifier) {
    val s = KronoTheme.status
    val (strong, band) = when (status) {
        MaintenanceStatus.OK -> s.ok to s.okContainer
        MaintenanceStatus.DUE_SOON -> s.dueSoon to s.dueSoonContainer
        MaintenanceStatus.OVERDUE -> s.overdue to s.overdueContainer
    }
    val axis = MaterialTheme.colorScheme.outlineVariant
    val today = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surfaceContainer
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = today)
    val measurer = rememberTextMeasurer()
    val description = MaintenanceFormats.window(window)

    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            val w = size.width
            val mid = size.height / 2
            fun x(f: Float) = f * w

            drawRoundRect(axis, Offset(0f, mid - 1.dp.toPx()), Size(w, 2.dp.toPx()), CornerRadius(1.dp.toPx()))

            // Band: at least as wide as the dot, so an exact calendar date still reads as a target.
            val bandH = 20.dp.toPx()
            val minBand = 20.dp.toPx()
            var left = x(window.windowStartFraction)
            var right = x(window.windowEndFraction)
            if (right - left < minBand) {
                val c = x(window.mostLikelyFraction)
                left = (c - minBand / 2).coerceIn(0f, w - minBand)
                right = left + minBand
            }
            drawRoundRect(band, Offset(left, mid - bandH / 2), Size(right - left, bandH), CornerRadius(bandH / 2))

            val dot = Offset(x(window.mostLikelyFraction), mid)
            drawCircle(strong, radius = 6.dp.toPx(), center = dot)
            drawCircle(ring, radius = 6.dp.toPx(), center = dot, style = Stroke(2.5.dp.toPx()))

            val nowX = x(window.nowFraction)
            drawRoundRect(today, Offset(nowX - 1.dp.toPx(), 2.dp.toPx()), Size(2.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(1.dp.toPx()))
            val label = measurer.measure("Today", labelStyle)
            val gap = 6.dp.toPx()
            // Flip the label to the left of the line when it would run off the right edge.
            val lx = if (nowX + gap + label.size.width > w) nowX - gap - label.size.width else nowX + gap
            drawText(label, topLeft = Offset(lx, 0f))
        }
        val labels = MaintenanceFormats.axisLabels(window)
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, text ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        labels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
