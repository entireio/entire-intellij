package io.entire.core.format

import io.entire.core.json.jsTrim
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private val DAY_OF_YEAR = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val DAY_WITH_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/**
 * Milliseconds since the epoch for a date as the Entire CLI writes it, or
 * null when the text is not a date. A date and time with no offset is local
 * time, and a date alone is midnight UTC.
 */
fun parseTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    try {
        return OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        // Not a date and time with an offset.
    }
    try {
        return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        // Not a date and time either.
    }
    return try {
        LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

/** "5m ago", "3h ago", "6d ago", then a date. Empty for an unknown time. */
fun relativeTime(time: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (time == null) return ""
    val elapsed = now - time
    if (elapsed < MINUTE) return "just now"
    if (elapsed < HOUR) return "${elapsed / MINUTE}m ago"
    if (elapsed < DAY) return "${elapsed / HOUR}h ago"
    if (elapsed < 30 * DAY) return "${elapsed / DAY}d ago"
    val date = Instant.ofEpochMilli(time).atZone(zone)
    val sameYear = date.year == Instant.ofEpochMilli(now).atZone(zone).year
    return (if (sameYear) DAY_OF_YEAR else DAY_WITH_YEAR).format(date)
}

fun relativeTime(time: String?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (time.isNullOrEmpty()) return ""
    return relativeTime(parseTime(time, zone), now, zone)
}

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun trimZero(value: String): String = if (value.endsWith(".0")) value.dropLast(2) else value

/** 950 -> "950", 12_400 -> "12.4K", 10_407_798 -> "10.4M". */
fun formatTokens(count: Long): String {
    if (count < 1000) return count.toString()
    var value = count.toDouble()
    var unit = ""
    for (next in listOf("K", "M", "B")) {
        value /= 1000
        unit = next
        // Rounding can reach 1000 (999,950 would read "1000K"), which belongs to the next unit.
        if (oneDecimal(value).toDouble() < 1000) break
    }
    return trimZero(oneDecimal(value)) + unit
}

/**
 * "12 of 249" for a checkpoint's place in its session. A plus sign after the
 * total means the session may have earlier checkpoints than the ones counted.
 */
fun formatPosition(ordinal: Int, total: Int, mayHaveEarlier: Boolean): String =
    String.format(Locale.US, "%,d of %,d%s", ordinal, total, if (mayHaveEarlier) "+" else "")

/** Shortens text to `limit` characters, ending in an ellipsis, without splitting a surrogate pair. */
fun truncate(text: String, limit: Int): String {
    if (text.length <= limit) return text
    var end = maxOf(0, limit - 1)
    if (end >= 1 && text[end - 1].isHighSurrogate()) end -= 1
    return text.substring(0, end) + "…"
}

/** The first non-empty line of `text`, shortened to `limit` characters. */
fun headline(text: String?, limit: Int = 90): String {
    if (text.isNullOrEmpty()) return ""
    val line = text.split('\n').map { it.jsTrim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    return truncate(line, limit)
}

/** Joins the parts that have content with a middle dot. */
fun joinParts(vararg parts: String?): String = parts.filter { !it.isNullOrEmpty() }.joinToString(" · ")
