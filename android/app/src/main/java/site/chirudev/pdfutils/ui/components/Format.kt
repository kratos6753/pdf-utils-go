package site.chirudev.pdfutils.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (value >= 100 || i == 0) String.format(Locale.US, "%.0f %s", value, units[i]) else String.format(Locale.US, "%.1f %s", value, units[i])
}

private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun formatDateTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(dateTime).orEmpty()
fun formatTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(time).orEmpty()
