package com.kalendrys.app.ui.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Conversões do seletor de lembrete. O DatePicker do Material 3 representa cada dia
 * como meia-noite em UTC; ler esse valor pelo fuso local volta um dia no Brasil (UTC-3).
 */
internal object ReminderTime {

    private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
    private val LAST_MINUTE: LocalTime = LocalTime.of(23, 59)

    fun dateToPickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun pickerMillisToDate(utcMillis: Long): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    fun toEpochMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun toLocalDateTime(epochMillis: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)

    /** Hoje: a próxima hora cheia, sem passar da meia-noite. Outros dias: 09:00. */
    fun suggestedTime(date: LocalDate, now: LocalDateTime): LocalTime {
        if (date != now.toLocalDate()) return DEFAULT_TIME
        val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        return if (nextHour.toLocalDate() == date) nextHour.toLocalTime() else LAST_MINUTE
    }
}
