package com.example.productivityapp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TaskDueParsing {

    /**
     * Serialize for PostgREST `timestamptz` and intent extras: same wall clock in the
     * device's default zone, as ISO-8601 **with offset** (unambiguous for Postgres).
     */
    fun toIsoParam(due: LocalDateTime): String =
        due.atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * Parse values from PostgREST (`date`, `timestamp`, `timestamptz`) or intent extras.
     */
    fun parseFlexible(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().replace(' ', 'T')
        return try {
            when {
                'T' in s || s.length > 10 -> {
                    try {
                        OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                    } catch (_: Exception) {
                        try {
                            Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        } catch (_: Exception) {
                            try {
                                LocalDateTime.parse(s)
                            } catch (_: Exception) {
                                LocalDateTime.parse(s.take(19))
                            }
                        }
                    }
                }
                else -> LocalDate.parse(s.take(10)).atStartOfDay()
            }
        } catch (_: Exception) {
            null
        }
    }
}
