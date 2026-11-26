package com.home.dashboard

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// How dates, times and temperatures are written, in one place.

private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, Locale.UK)

internal val HHmm = pattern("HH:mm")
internal val WeekdayFmt = pattern("EEEE")
internal val DayMonthFmt = pattern("d MMMM")
internal val LongDayFmt = pattern("EEEE d MMMM")
internal val ShortDayFmt = pattern("EEE d MMM")
internal val DowFmt = pattern("EEE")
internal val MonthFmt = pattern("MMMM")
internal val MonthYearFmt = pattern("MMMM yyyy")
internal val ShortMonthFmt = pattern("MMM")

private fun zoned(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

internal fun clockTime(millis: Long): String = zoned(millis).format(HHmm)

/** The time of day, with the weekday once it isn't today. */
internal fun readingTime(millis: Long): String =
    if (zoned(millis).toLocalDate() == LocalDate.now()) clockTime(millis) else "${zoned(millis).format(DowFmt)} ${clockTime(millis)}"

/** A temperature, or "Off" when there is none. */
internal fun deg(t: Double?): String =
    if (t == null) "Off" else String.format(Locale.UK, "%.1f", t).removeSuffix(".0") + "°"
