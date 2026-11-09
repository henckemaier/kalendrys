package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineScheduleTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    // Sábado, 24/10/2026.
    private val saturday = LocalDate.of(2026, 10, 24)

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private fun routine(
        id: Long = 1,
        startMinute: Int = 7 * 60,
        days: Int = RoutineDays.ALL,
        createdOn: LocalDate = saturday.minusDays(30),
        archivedOn: LocalDate? = null,
        weekInterval: Int = 1,
        startEpochDay: Long? = null,
        endEpochDay: Long? = null,
    ) = Routine(
        id = id,
        title = "Rotina $id",
        startMinute = startMinute,
        durationMinutes = 30,
        daysOfWeek = days,
        createdAt = millis(createdOn.atTime(18, 0)),
        archivedAt = archivedOn?.let { millis(it.atTime(10, 0)) },
        weekInterval = weekInterval,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
    )

    private fun override(
        routineId: Long = 1,
        on: LocalDate,
        type: String,
        newStartMinute: Int? = null,
        newEpochDay: Long? = null,
    ) = RoutineOverride(
        id = 0,
        routineId = routineId,
        epochDay = on.toEpochDay(),
        type = type,
        newStartMinute = newStartMinute,
        newEpochDay = newEpochDay,
    )

    @Test
    fun `ocorre so nos dias marcados`() {
        val weekdays = routine(days = RoutineDays.WEEKDAYS)
        assertFalse(RoutineSchedule.occursOn(weekdays, saturday, zone))
        assertTrue(RoutineSchedule.occursOn(weekdays, saturday.plusDays(2), zone)) // segunda
    }

    @Test
    fun `ocorre a partir do dia da criacao mesmo criada depois do horario`() {
        val created = routine(createdOn = saturday)
        assertFalse(RoutineSchedule.occursOn(created, saturday.minusDays(1), zone))
        assertTrue(RoutineSchedule.occursOn(created, saturday, zone))
    }

    @Test
    fun `rotina excluida some do dia da exclusao em diante e fica nos anteriores`() {
        val archived = routine(archivedOn = saturday)
        assertTrue(RoutineSchedule.occursOn(archived, saturday.minusDays(1), zone))
        assertFalse(RoutineSchedule.occursOn(archived, saturday, zone))
        assertNull(RoutineSchedule.nextStart(archived, saturday.atTime(6, 0), zone))
    }

    @Test
    fun `proximo inicio e hoje se ainda nao passou senao no proximo dia marcado`() {
        val weekdays = routine(days = RoutineDays.WEEKDAYS)
        val friday = saturday.minusDays(1)
        assertEquals(friday.atTime(7, 0), RoutineSchedule.nextStart(weekdays, friday.atTime(6, 59), zone))
        // Sexta 07:00 já passou: o próximo é segunda.
        assertEquals(saturday.plusDays(2).atTime(7, 0), RoutineSchedule.nextStart(weekdays, friday.atTime(7, 0), zone))
    }

    @Test
    fun `sem dias marcados nao ha proximo inicio`() {
        assertNull(RoutineSchedule.nextStart(routine(days = 0), saturday.atTime(6, 0), zone))
    }

    @Test
    fun `proximo inicio entre varias rotinas junta as que comecam no mesmo horario`() {
        val routines = listOf(
            routine(id = 1, startMinute = 9 * 60),
            routine(id = 2, startMinute = 8 * 60),
            routine(id = 3, startMinute = 8 * 60),
        )
        val next = RoutineSchedule.nextStart(routines, saturday.atTime(7, 30), zone)
        assertEquals(saturday.atTime(8, 0), next?.at)
        assertEquals(listOf(2L, 3L), next?.routines?.map { it.id })
    }

    @Test
    fun `segue o relogio local no inicio do horario de verao`() {
        // Em 2018 o horário de verão de São Paulo começou em 04/11: 00:00 pulou para 01:00.
        val day = LocalDate.of(2018, 11, 4)
        val routine = routine(startMinute = 7 * 60, createdOn = day.minusDays(1))
        val next = RoutineSchedule.nextStart(routine, day.atTime(0, 30), zone)
        assertEquals(day.atTime(7, 0), next)
    }

    // --- T19: recorrência rica (a cada N semanas + janela) ---

    @Test
    fun `a cada 2 semanas ocorre so nas semanas de paridade par a partir da ancora`() {
        // Âncora na segunda 19/10/2026; semana 0 ocorre, semana 1 pula, semana 2 ocorre.
        val monday = LocalDate.of(2026, 10, 19)
        val r = routine(weekInterval = 2, startEpochDay = monday.toEpochDay(), createdOn = monday.minusDays(10))
        assertTrue(RoutineSchedule.occursOn(r, LocalDate.of(2026, 10, 21), zone)) // quarta, semana 0
        assertFalse(RoutineSchedule.occursOn(r, LocalDate.of(2026, 10, 28), zone)) // quarta, semana 1
        assertTrue(RoutineSchedule.occursOn(r, LocalDate.of(2026, 11, 4), zone)) // quarta, semana 2
    }

    @Test
    fun `a cada 2 semanas - proximo inicio pula a semana impar`() {
        val monday = LocalDate.of(2026, 10, 19)
        val r = routine(weekInterval = 2, startEpochDay = monday.toEpochDay(), createdOn = monday.minusDays(10))
        // Depois do fim da semana 0 (domingo 25/10), o próximo é a segunda da semana 2 (02/11), não a da semana 1.
        val next = RoutineSchedule.nextStart(r, LocalDate.of(2026, 10, 25).atTime(23, 0), zone)
        assertEquals(LocalDate.of(2026, 11, 2).atTime(7, 0), next)
    }

    @Test
    fun `data de inicio futura adia a primeira ocorrencia`() {
        val start = saturday.plusDays(7)
        val r = routine(startEpochDay = start.toEpochDay())
        assertFalse(RoutineSchedule.occursOn(r, saturday, zone))
        assertTrue(RoutineSchedule.occursOn(r, start, zone))
        assertEquals(start.atTime(7, 0), RoutineSchedule.nextStart(r, saturday.atTime(6, 0), zone))
    }

    @Test
    fun `data de termino encerra a rotina`() {
        val r = routine(endEpochDay = saturday.toEpochDay())
        assertTrue(RoutineSchedule.occursOn(r, saturday, zone))
        assertFalse(RoutineSchedule.occursOn(r, saturday.plusDays(1), zone))
        // Depois do fim não há mais próximo início.
        assertNull(RoutineSchedule.nextStart(r, saturday.atTime(23, 0), zone))
    }

    // --- T20: exceções pontuais (SKIP / RESCHEDULE) ---

    @Test
    fun `excecao SKIP remove a ocorrencia do dia`() {
        val r = routine()
        val skip = listOf(override(on = saturday, type = RoutineOverrideType.SKIP))
        assertFalse(RoutineSchedule.occursOn(r, saturday, zone, skip))
        assertTrue(RoutineSchedule.occursOn(r, saturday.plusDays(1), zone, skip)) // outros dias intactos
    }

    @Test
    fun `excecao RESCHEDULE muda o minuto no mesmo dia`() {
        val r = routine(startMinute = 7 * 60)
        val resched = listOf(override(on = saturday, type = RoutineOverrideType.RESCHEDULE, newStartMinute = 9 * 60))
        assertTrue(RoutineSchedule.occursOn(r, saturday, zone, resched))
        assertEquals(saturday.atTime(9, 0), RoutineSchedule.startOn(r, saturday, resched))
    }

    @Test
    fun `excecao RESCHEDULE move a ocorrencia para outro dia`() {
        val r = routine(days = RoutineDays.WEEKDAYS, startMinute = 7 * 60)
        val friday = saturday.minusDays(1)
        val target = saturday // sábado normalmente não ocorre (só dias úteis)
        val resched = listOf(
            override(on = friday, type = RoutineOverrideType.RESCHEDULE, newStartMinute = 10 * 60, newEpochDay = target.toEpochDay()),
        )
        assertFalse(RoutineSchedule.occursOn(r, friday, zone, resched)) // saiu do dia de origem
        assertTrue(RoutineSchedule.occursOn(r, target, zone, resched)) // entrou no dia de destino
        assertEquals(target.atTime(10, 0), RoutineSchedule.startOn(r, target, resched))
    }

    @Test
    fun `proximo inicio honra o SKIP pulando para o proximo dia marcado`() {
        val r = routine(days = RoutineDays.WEEKDAYS)
        val friday = saturday.minusDays(1)
        val skip = listOf(override(on = friday, type = RoutineOverrideType.SKIP))
        // Sexta pulada: o próximo é segunda (sáb/dom não são dias úteis).
        assertEquals(
            saturday.plusDays(2).atTime(7, 0),
            RoutineSchedule.nextStart(r, friday.atTime(6, 0), zone, skip),
        )
    }
}
