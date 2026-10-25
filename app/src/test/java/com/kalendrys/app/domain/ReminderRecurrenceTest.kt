package com.kalendrys.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderRecurrenceTest {

    // Sábado, 24/10/2026, 09:00.
    private val anchor = LocalDateTime.of(2026, 10, 24, 9, 0)

    private fun next(repeat: ReminderRepeat, after: LocalDateTime, from: LocalDateTime = anchor) =
        ReminderRecurrence.nextOccurrence(from, repeat, after)

    @Test
    fun `lembrete unico toca na ancora e depois acaba`() {
        assertEquals(anchor, next(ReminderRepeat.NONE, anchor.minusHours(1)))
        assertNull(next(ReminderRepeat.NONE, anchor))
    }

    @Test
    fun `diario toca hoje se o horario ainda nao passou e amanha se ja passou`() {
        val tomorrow = LocalDateTime.of(2026, 10, 30, 9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 29, 9, 0), next(ReminderRepeat.DAILY, LocalDateTime.of(2026, 10, 29, 8, 0)))
        assertEquals(tomorrow, next(ReminderRepeat.DAILY, LocalDateTime.of(2026, 10, 29, 9, 0)))
    }

    @Test
    fun `dias uteis pula o fim de semana`() {
        // Sexta 30/10 depois das 09:00 -> segunda 02/11.
        assertEquals(
            LocalDateTime.of(2026, 11, 2, 9, 0),
            next(ReminderRepeat.WEEKDAYS, LocalDateTime.of(2026, 10, 30, 10, 0)),
        )
    }

    @Test
    fun `dias uteis com ancora no sabado comeca na segunda`() {
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), next(ReminderRepeat.WEEKDAYS, anchor.minusHours(1)))
    }

    @Test
    fun `semanal repete no mesmo dia da semana da ancora`() {
        assertEquals(LocalDateTime.of(2026, 10, 31, 9, 0), next(ReminderRepeat.WEEKLY, anchor))
    }

    @Test
    fun `mensal do dia 31 cai no ultimo dia de fevereiro e volta ao 31 em marco`() {
        val day31 = LocalDateTime.of(2027, 1, 31, 9, 0)
        assertEquals(LocalDateTime.of(2027, 2, 28, 9, 0), next(ReminderRepeat.MONTHLY, day31, from = day31))
        assertEquals(
            LocalDateTime.of(2027, 3, 31, 9, 0),
            next(ReminderRepeat.MONTHLY, LocalDateTime.of(2027, 2, 28, 9, 0), from = day31),
        )
    }

    @Test
    fun `nada acontece antes da ancora`() {
        assertEquals(anchor, next(ReminderRepeat.DAILY, LocalDateTime.of(2026, 10, 1, 0, 0)))
    }

    @Test
    fun `ocorrencias no mes respeitam a regra e o inicio na ancora`() {
        val days = ReminderRecurrence.occurrencesBetween(
            anchor,
            ReminderRepeat.WEEKLY,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31)), days)
    }

    @Test
    fun `horario diario e hoje se ainda nao passou e amanha se ja passou`() {
        val eight = LocalTime.of(8, 0)
        assertEquals(LocalDateTime.of(2026, 10, 24, 8, 0), ReminderRecurrence.nextDailyAt(eight, LocalDateTime.of(2026, 10, 24, 7, 59)))
        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 0), ReminderRecurrence.nextDailyAt(eight, LocalDateTime.of(2026, 10, 24, 8, 0)))
    }

    @Test
    fun `proximo alarme em millis usa o horario local`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val anchorMillis = anchor.atZone(zone).toInstant().toEpochMilli()
        val now = Instant.parse("2026-10-24T13:00:00Z").toEpochMilli() // 10:00 em São Paulo
        val expected = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli() // amanhã, 09:00 local

        assertEquals(expected, ReminderRecurrence.nextTriggerMillis(anchorMillis, ReminderRepeat.DAILY, now, zone))
        assertNull(ReminderRecurrence.nextTriggerMillis(anchorMillis, ReminderRepeat.NONE, now, zone))
    }
}
