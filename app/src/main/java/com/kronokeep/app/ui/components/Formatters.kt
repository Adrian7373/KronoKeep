package com.kronokeep.app.ui.components

import com.kronokeep.app.domain.asset.AssetError
import com.kronokeep.app.domain.model.DocumentKind
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Philippine English formatting (dates like "14 Nov 2026", km with thousands separators). */
object Formats {
    val LOCALE: Locale = Locale.forLanguageTag("en-PH")
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", LOCALE)
    private val NUMBER = NumberFormat.getIntegerInstance(LOCALE)

    fun date(date: LocalDate): String = DATE.format(date)

    fun date(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = DATE.format(instant.atZone(zone))

    private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", LOCALE)
    private val MONTH = DateTimeFormatter.ofPattern("MMM", LOCALE)

    /** "30 Nov": for windows and axes, where the year is obvious. */
    fun shortDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = SHORT_DATE.format(instant.atZone(zone))

    fun month(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = MONTH.format(instant.atZone(zone))

    fun km(km: Int): String = "${NUMBER.format(km)} km"

    fun bytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${(size + 512) / 1024} KB"
        else -> String.format(LOCALE, "%.1f MB", size / (1024.0 * 1024.0))
    }

    /** Material date pickers speak UTC-midnight millis. */
    fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun fromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    fun warranty(expiresOn: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, expiresOn)
        return when {
            days < 0 -> "Expired ${date(expiresOn)}"
            days == 0L -> "Expires today"
            days <= 60 -> "Expires in $days day${if (days == 1L) "" else "s"} · ${date(expiresOn)}"
            else -> "Until ${date(expiresOn)}"
        }
    }
}

val DocumentKind.label: String
    get() = when (this) {
        DocumentKind.RECEIPT -> "Receipt"
        DocumentKind.WARRANTY -> "Warranty card"
        DocumentKind.MANUAL -> "Manual"
        DocumentKind.PHOTO -> "Photo"
        DocumentKind.OTHER -> "Other"
    }

fun AssetError.message(): String = when (this) {
    AssetError.Required -> "Required"
    is AssetError.TooLong -> "Keep it under $max characters"
    AssetError.Negative -> "Can't be negative"
    AssetError.InFuture -> "Can't be in the future"
    AssetError.BeforePurchase -> "Must be on or after the purchase date"
    is AssetError.TooLarge -> "That's more than ${NumberFormat.getIntegerInstance(Formats.LOCALE).format(max)}"
    AssetError.InvalidNumber -> "Enter a number"
}
