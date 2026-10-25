package com.kalendrys.app.ui.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTimeTest {

    private val saoPaulo: ZoneId = ZoneId.of("America/Sao_Paulo")

    @Test
    fun `millis do DatePicker representam meia-noite em UTC`() {
        val millis = ReminderTime.dateToPickerMillis(LocalDate.of(2026, 10, 24))
        assertEquals(Instant.parse("2026-10-24T00:00:00Z").toEpochMilli(), millis)
    }

    @Test
    fun `data escolhida no DatePicker nao volta um dia no fuso do Brasil`() {
        val millis = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 24), ReminderTime.pickerMillisToDate(millis))
    }

    @Test
    fun `data e hora sao combinadas no fuso local`() {
        val millis = ReminderTime.toEpochMillis(LocalDate.of(2026, 10, 24), LocalTime.of(18, 30), saoPaulo)
        assertEquals(Instant.parse("2026-10-24T21:30:00Z").toEpochMilli(), millis)
    }

    @Test
    fun `ida e volta de epoch millis preserva data e hora`() {
        val original = LocalDateTime.of(2026, 12, 31, 23, 45)
        val millis = ReminderTime.toEpochMillis(original.toLocalDate(), original.toLocalTime(), saoPaulo)
        assertEquals(original, ReminderTime.toLocalDateTime(millis, saoPaulo))
    }

    @Test
    fun `sugestao para hoje e a proxima hora cheia`() {
        val now = LocalDateTime.of(2026, 10, 24, 16, 20)
        assertEquals(LocalTime.of(17, 0), ReminderTime.suggestedTime(now.toLocalDate(), now))
    }

    @Test
    fun `sugestao para hoje nao passa da meia-noite`() {
        val now = LocalDateTime.of(2026, 10, 24, 23, 10)
        assertEquals(LocalTime.of(23, 59), ReminderTime.suggestedTime(now.toLocalDate(), now))
    }

    @Test
    fun `sugestao para outro dia e nove da manha`() {
        val now = LocalDateTime.of(2026, 10, 24, 16, 20)
        assertEquals(LocalTime.of(9, 0), ReminderTime.suggestedTime(now.toLocalDate().plusDays(1), now))
    }
}
