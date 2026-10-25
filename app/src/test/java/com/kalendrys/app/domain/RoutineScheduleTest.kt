package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
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
    ) = Routine(
        id = id,
        title = "Rotina $id",
        startMinute = startMinute,
        durationMinutes = 30,
        daysOfWeek = days,
        createdAt = millis(createdOn.atTime(18, 0)),
        archivedAt = archivedOn?.let { millis(it.atTime(10, 0)) },
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
}
