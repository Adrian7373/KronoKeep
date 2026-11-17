package com.kronokeep.app.ui.maintenance

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kronokeep.app.domain.engine.DuePrediction
import com.kronokeep.app.domain.engine.RateSource
import com.kronokeep.app.domain.engine.UsageRate
import com.kronokeep.app.domain.maintenance.DueWindow
import com.kronokeep.app.domain.maintenance.MaintenanceError
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.ui.components.Formats
import com.kronokeep.app.ui.theme.KronoTheme
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

object MaintenanceFormats {
    /** "every 2 months or 1,500 km", "every 45 days". Months only for whole multiples of 30. */
    fun interval(days: Int?, km: Int?): String {
        val d = days?.let(::period)
        val k = km?.let(Formats::km)
        return "Every " + listOfNotNull(d, k).joinToString(" or ")
    }

    fun period(days: Int): String = when {
        days % 365 == 0 -> plural(days / 365, "year")
        days % 30 == 0 -> plural(days / 30, "month")
        days % 7 == 0 && days < 30 -> plural(days / 7, "week")
        else -> plural(days, "day")
    }

    /**
     * Headline for a prediction. Usage-based dates carry their ± window so the UI never pretends to know
     * the exact day; calendar dates are exact.
     */
    fun due(p: DuePrediction, now: Instant): String {
        val at = p.nextDueAt
        val km = p.nextDueKm?.let(Formats::km)
        return when {
            at == null && km != null -> "Due at $km"
            at == null -> "Not enough data to predict"
            p.status == MaintenanceStatus.OVERDUE -> {
                val days = Duration.between(at, now).toDays()
                val since = if (days <= 0) "today" else "${plural(days.toInt(), "day")} ago"
                "Overdue · was due $since"
            }
            else -> {
                val days = Duration.between(now, at).toDays().toInt()
                val whenText = when (days) {
                    0 -> "today"
                    1 -> "tomorrow"
                    else -> "in ${plural(days, "day")}"
                }
                val window = p.uncertaintyDays?.takeIf { it > 0 }?.let { " ± $it d" }.orEmpty()
                "Due $whenText$window"
            }
        }
    }

    /** Secondary line: the date and/or km the task falls due at. */
    fun dueDetail(p: DuePrediction): String = listOfNotNull(
        p.nextDueAt?.let { (if ((p.uncertaintyDays ?: 0) > 0) "around " else "") + Formats.date(it) },
        p.nextDueKm?.let { "at ${Formats.km(it)}" },
    ).joinToString(" · ")

    fun rate(rate: UsageRate): String {
        val perDay = rate.kmPerDay?.let { "~${it.roundToInt()} km/day" }
        return when (rate.source) {
            RateSource.EWMA -> "$perDay from your odometer readings · ${confidenceWord(rate.confidence)} confidence"
            RateSource.BASELINE -> "$perDay from your weekly estimate. Update the odometer to sharpen this."
            RateSource.NONE -> "Add odometer readings or a weekly km estimate to predict km-based tasks."
        }
    }

    /**
     * Short label for an asset tag's pill: "Oil change overdue", "PMS in 9–13 d", "Cleaning in 12 d",
     * "Oil change today", "Oil change at 8,000 km". Null when the task can't be predicted at all.
     */
    fun tagLabel(task: String, p: DuePrediction, now: Instant): String? {
        if (p.status == MaintenanceStatus.OVERDUE) return "$task overdue"
        val at = p.nextDueAt ?: return p.nextDueKm?.let { "$task at ${Formats.km(it)}" }
        val days = Duration.between(now, at).toDays().toInt().coerceAtLeast(0)
        val u = p.uncertaintyDays ?: 0
        return when {
            u > 0 -> {
                val lo = (days - u).coerceAtLeast(0)
                if (lo == 0 && days + u == 0) "$task today" else "$task in $lo–${days + u} d"
            }
            days == 0 -> "$task today"
            else -> "$task in $days d"
        }
    }

    /** "Most likely 30 Nov. Window 23 Nov to 2 Dec." or, for an exact calendar date, "Due 30 Nov 2026." */
    fun window(w: DueWindow, zone: ZoneId = ZoneId.systemDefault()): String =
        if (w.isPoint) {
            "Due ${Formats.date(w.mostLikely, zone)}."
        } else {
            "Most likely ${Formats.shortDate(w.mostLikely, zone)}. " +
                "Window ${Formats.shortDate(w.windowStart, zone)} to ${Formats.shortDate(w.windowEnd, zone)}."
        }

    /** Three axis labels (start, middle, end of the track): months for long tracks, days for short ones. */
    fun axisLabels(w: DueWindow, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val span = Duration.between(w.start, w.end)
        val mid = w.start + span.dividedBy(2)
        val long = span.toDays() > 75
        return listOf(w.start, mid, w.end).map { if (long) Formats.month(it, zone) else Formats.shortDate(it, zone) }
    }

    /** "Today", "Yesterday", "12 d ago". Future instants (clock skew) read as today. */
    fun ago(at: Instant, now: Instant): String = when (val d = Duration.between(at, now).toDays()) {
        in Long.MIN_VALUE..0L -> "Today"
        1L -> "Yesterday"
        else -> "$d d ago"
    }

    fun confidenceWord(c: Double): String = when {
        c >= 0.75 -> "high"
        c >= 0.45 -> "medium"
        else -> "low"
    }

    fun statusLabel(status: MaintenanceStatus): String = when (status) {
        MaintenanceStatus.OK -> "OK"
        MaintenanceStatus.DUE_SOON -> "Due soon"
        MaintenanceStatus.OVERDUE -> "Overdue"
    }

    fun plural(n: Int, unit: String): String = "$n $unit${if (n == 1) "" else "s"}"
}

fun MaintenanceError.message(): String = when (this) {
    MaintenanceError.Required -> "Required"
    is MaintenanceError.TooLong -> "Keep it under $max characters"
    MaintenanceError.Negative -> "Can't be negative"
    MaintenanceError.NotPositive -> "Must be more than 0"
    is MaintenanceError.TooLarge -> "That's more than ${NumberFormat.getIntegerInstance(Formats.LOCALE).format(max)}"
    MaintenanceError.InFuture -> "Can't be in the future"
    MaintenanceError.NoInterval -> "Set days, km, or both"
    is MaintenanceError.AboveOdometer -> "Higher than the current odometer (${Formats.km(currentKm)})"
    is MaintenanceError.BelowLastReading -> "Lower than the last reading (${Formats.km(lastKm)})"
    MaintenanceError.OdometerNeeded -> "Needed for km-based tasks"
    MaintenanceError.NothingLogged -> "Tick a task or describe the repair"
    MaintenanceError.InvalidNumber -> "Enter a number"
}

/** Coloured status pill (OK / Due soon / Overdue), optionally with a custom label in the status colours. */
@Composable
fun StatusPill(status: MaintenanceStatus, modifier: Modifier = Modifier, label: String = MaintenanceFormats.statusLabel(status)) {
    val s = KronoTheme.status
    val (container, content) = when (status) {
        MaintenanceStatus.OK -> s.okContainer to s.onOkContainer
        MaintenanceStatus.DUE_SOON -> s.dueSoonContainer to s.onDueSoonContainer
        MaintenanceStatus.OVERDUE -> s.overdueContainer to s.onOverdueContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
