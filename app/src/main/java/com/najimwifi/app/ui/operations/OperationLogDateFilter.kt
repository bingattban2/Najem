package com.najimwifi.app.ui.operations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Accept Arabic/Persian digits and either slash or dash without silent date rollover. */
internal object OperationLogDateFilter {
    private val calendarDate = DateTimeFormatter.ofPattern("uuuu-M-d")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        val normalized = buildString {
            for (ch in raw.trim()) {
                append(when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '/' -> '-'
                    else -> ch
                })
            }
        }
        return runCatching { LocalDate.parse(normalized, calendarDate) }.getOrNull()
    }

    fun validRange(fromRaw: String, toRaw: String): Boolean {
        val from = parse(fromRaw)
        val to = parse(toRaw)
        return (fromRaw.isBlank() || from != null) &&
            (toRaw.isBlank() || to != null) &&
            (from == null || to == null || !from.isAfter(to))
    }
}
