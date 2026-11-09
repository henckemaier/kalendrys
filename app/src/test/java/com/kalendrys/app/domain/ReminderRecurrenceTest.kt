package com.kalendrys.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- CUSTOM: multi-seleção de dias-da-semana. Âncora é sábado 24/10/2026 09:00. ---
    // bitmask SEG=bit0, QUA=bit2, SEX=bit4 -> seg/qua/sex.
    private val monWedFri = (1 shl 0) or (1 shl 2) or (1 shl 4)

    @Test
    fun `custom por dias-da-semana ocorre so nos dias escolhidos`() {
        fun occurs(d: LocalDate) =
            ReminderRecurrence.occursOn(d, anchor, ReminderRepeat.CUSTOM, byWeekdays = monWedFri)
        assertTrue(occurs(LocalDate.of(2026, 10, 26))) // segunda
        assertTrue(occurs(LocalDate.of(2026, 10, 28))) // quarta
        assertTrue(occurs(LocalDate.of(2026, 10, 30))) // sexta
        assertFalse(occurs(LocalDate.of(2026, 10, 27))) // terça
        assertFalse(occurs(LocalDate.of(2026, 10, 24))) // sábado = âncora, não selecionado
    }

    @Test
    fun `custom por dias-da-semana pula ate o proximo dia escolhido`() {
        // Sábado 09:00 -> próxima é segunda 26/10 09:00.
        assertEquals(
            LocalDateTime.of(2026, 10, 26, 9, 0),
            ReminderRecurrence.nextOccurrence(anchor, ReminderRepeat.CUSTOM, anchor, byWeekdays = monWedFri),
        )
    }

    @Test
    fun `custom occurrencesBetween lista os dias escolhidos no mes`() {
        val days = ReminderRecurrence.occurrencesBetween(
            anchor,
            ReminderRepeat.CUSTOM,
            LocalDate.of(2026, 10, 24),
            LocalDate.of(2026, 10, 31),
            byWeekdays = monWedFri,
        )
        assertEquals(
            listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30)),
            days,
        )
    }

    @Test
    fun `custom a cada 3 dias conta da ancora`() {
        fun occurs(d: LocalDate) =
            ReminderRecurrence.occursOn(d, anchor, ReminderRepeat.CUSTOM, every = 3, unit = RecurrenceUnit.DAYS)
        assertTrue(occurs(LocalDate.of(2026, 10, 24))) // +0
        assertTrue(occurs(LocalDate.of(2026, 10, 27))) // +3
        assertTrue(occurs(LocalDate.of(2026, 10, 30))) // +6
        assertFalse(occurs(LocalDate.of(2026, 10, 25))) // +1
        assertEquals(
            LocalDateTime.of(2026, 10, 27, 9, 0),
            ReminderRecurrence.nextOccurrence(anchor, ReminderRepeat.CUSTOM, anchor, every = 3, unit = RecurrenceUnit.DAYS),
        )
    }

    @Test
    fun `custom a cada 2 semanas repete no dia da semana da ancora`() {
        fun occurs(d: LocalDate) =
            ReminderRecurrence.occursOn(d, anchor, ReminderRepeat.CUSTOM, every = 2, unit = RecurrenceUnit.WEEKS)
        assertTrue(occurs(LocalDate.of(2026, 10, 24))) // semana 0 (sábado)
        assertFalse(occurs(LocalDate.of(2026, 10, 31))) // semana 1
        assertTrue(occurs(LocalDate.of(2026, 11, 7))) // semana 2 (sábado)
        assertFalse(occurs(LocalDate.of(2026, 11, 6))) // sexta: dia-da-semana errado
        assertEquals(
            LocalDateTime.of(2026, 11, 7, 9, 0),
            ReminderRecurrence.nextOccurrence(anchor, ReminderRepeat.CUSTOM, anchor, every = 2, unit = RecurrenceUnit.WEEKS),
        )
    }

    @Test
    fun `custom a cada 3 meses aplica o clamp do dia do mes`() {
        val day31 = LocalDateTime.of(2026, 12, 31, 9, 0)
        fun occurs(d: LocalDate) =
            ReminderRecurrence.occursOn(d, day31, ReminderRepeat.CUSTOM, every = 3, unit = RecurrenceUnit.MONTHS)
        assertTrue(occurs(LocalDate.of(2026, 12, 31))) // mês 0
        assertFalse(occurs(LocalDate.of(2027, 1, 31))) // mês 1 (fora do ciclo de 3)
        assertTrue(occurs(LocalDate.of(2027, 3, 31))) // mês 3
        assertTrue(occurs(LocalDate.of(2027, 6, 30))) // mês 6, junho tem 30 -> clamp do 31
        assertFalse(occurs(LocalDate.of(2027, 6, 29)))
        // next após o dia 0 -> março 31; a seguinte -> junho 30 (clamp).
        assertEquals(
            LocalDateTime.of(2027, 3, 31, 9, 0),
            ReminderRecurrence.nextOccurrence(day31, ReminderRepeat.CUSTOM, day31, every = 3, unit = RecurrenceUnit.MONTHS),
        )
        assertEquals(
            LocalDateTime.of(2027, 6, 30, 9, 0),
            ReminderRecurrence.nextOccurrence(
                day31,
                ReminderRepeat.CUSTOM,
                LocalDateTime.of(2027, 3, 31, 9, 0),
                every = 3,
                unit = RecurrenceUnit.MONTHS,
            ),
        )
    }

    @Test
    fun `custom nextTriggerMillis respeita o fuso`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val anchorMillis = anchor.atZone(zone).toInstant().toEpochMilli()
        // Exatamente na âncora; a cada 2 dias -> próxima é 26/10 09:00 local.
        val expected = LocalDateTime.of(2026, 10, 26, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            expected,
            ReminderRecurrence.nextTriggerMillis(
                anchorMillis,
                ReminderRepeat.CUSTOM,
                anchorMillis,
                zone,
                every = 2,
                unit = RecurrenceUnit.DAYS,
            ),
        )
    }

    @Test
    fun `custom sem configuracao nunca ocorre`() {
        assertFalse(ReminderRecurrence.occursOn(LocalDate.of(2026, 10, 24), anchor, ReminderRepeat.CUSTOM))
        assertNull(ReminderRecurrence.nextOccurrence(anchor, ReminderRepeat.CUSTOM, anchor.minusHours(1)))
    }
}
