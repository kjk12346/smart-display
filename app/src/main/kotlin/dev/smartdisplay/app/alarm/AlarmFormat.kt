package dev.smartdisplay.app.alarm

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/** An alarm's time in the device's 12- or 24-hour style ("7:30 AM", "07:30"). */
fun alarmTimeText(context: Context, alarm: Alarm): String = timeText(context, alarm.time)

/**
 * An alarm's time split for large display: the digits ("7:30") and the AM/PM marker, or null in 24-hour style, so
 * the marker can be drawn smaller beside the digits.
 */
fun alarmTimeParts(context: Context, alarm: Alarm): Pair<String, String?> {
    val locale = Locale.getDefault()
    if (DateFormat.is24HourFormat(context)) return timeText(context, alarm.time) to null
    val digits = DateFormat.getBestDateTimePattern(locale, "hm").replace("a", "").trim()
    return format(alarm.time, digits, locale) to format(alarm.time, "a", locale)
}

/** When the next alarm rings: the time, with the weekday unless it's within the next day ("Sat 7:30 AM"). */
fun nextRingText(context: Context, next: NextRing, now: ZonedDateTime = ZonedDateTime.now()): String {
    val time = timeText(context, next.at)
    val soon = next.at.toLocalDate() <= LocalDate.from(now).plusDays(1) &&
        next.at.isBefore(now.plusHours(24))
    return if (soon) time else format(next.at, "EEE", Locale.getDefault()) + " " + time
}

private fun timeText(context: Context, time: TemporalAccessor): String {
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
    return format(time, DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

private fun format(time: TemporalAccessor, pattern: String, locale: Locale): String = try {
    DateTimeFormatter.ofPattern(pattern, locale).format(time)
} catch (e: IllegalArgumentException) {
    DateTimeFormatter.ofPattern("H:mm", locale).format(time)
}
