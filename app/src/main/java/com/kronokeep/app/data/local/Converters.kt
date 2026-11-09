package com.kronokeep.app.data.local

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/** Instants are stored as epoch millis (UTC); plain dates as epoch days. Enums use Room's built-in name mapping. */
class Converters {
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun dateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
}
