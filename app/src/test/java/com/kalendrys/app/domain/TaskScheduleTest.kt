package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.TaskSchedule.Status
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScheduleTest {

    // Quarta-feira, 28/10/2026.
    private val today = LocalDate.of(2026, 10, 28)

    private fun task(due: LocalDate?, repeat: ReminderRepeat = ReminderRepeat.NONE) =
        Task(id = 1, title = "T", dueDate = due?.toEpochDay(), repeat = repeat)

    @Test
    fun `sem repeticao concluida em outro dia esta encerrada e nao volta`() {
        val oneTime = task(today.minusDays(1))
        assertEquals(Status.DONE, TaskSchedule.status(oneTime, today, doneEver = true, doneToday = false))
        assertEquals(Status.DONE_TODAY, TaskSchedule.status(oneTime, today, doneEver = true, doneToday = true))
    }

    @Test
    fun `sem repeticao pendente fica atrasada hoje proxima ou sem data`() {
        assertEquals(Status.OVERDUE, TaskSchedule.status(task(today.minusDays(2)), today, false, false))
        assertEquals(Status.TODAY, TaskSchedule.status(task(today), today, false, false))
        assertEquals(Status.UPCOMING, TaskSchedule.status(task(today.plusDays(1)), today, false, false))
        assertEquals(Status.NO_DATE, TaskSchedule.status(task(null), today, false, false))
    }

    @Test
    fun `que se repete vale por dia e nunca fica atrasada`() {
        val daily = task(today.minusDays(10), ReminderRepeat.DAILY)
        // Ter sido feita em outro dia não encerra: hoje ela está pendente de novo.
        assertEquals(Status.TODAY, TaskSchedule.status(daily, today, doneEver = true, doneToday = false))
        assertEquals(Status.DONE_TODAY, TaskSchedule.status(daily, today, doneEver = true, doneToday = true))
        // Dias úteis num sábado: só na próxima segunda.
        val weekdays = task(today.minusDays(10), ReminderRepeat.WEEKDAYS)
        assertEquals(Status.UPCOMING, TaskSchedule.status(weekdays, LocalDate.of(2026, 10, 31), false, false))
    }

    @Test
    fun `proxima ocorrencia`() {
        val weekly = task(today, ReminderRepeat.WEEKLY)
        assertEquals(today, TaskSchedule.nextOccurrence(weekly, today))
        assertEquals(today.plusWeeks(1), TaskSchedule.nextOccurrence(weekly, today.plusDays(1)))
        assertNull(TaskSchedule.nextOccurrence(task(today.minusDays(1)), today))
        assertNull(TaskSchedule.nextOccurrence(task(null), today))
    }

    private val zone = java.time.ZoneId.of("America/Sao_Paulo")

    private fun millis(date: LocalDate) = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `encerrada so com conclusao no dia dela ou depois`() {
        val due = task(today)
        assertTrue(TaskSchedule.isFinished(due, millis(today), zone))
        assertTrue(TaskSchedule.isFinished(due, millis(today.plusDays(1)), zone))
        // Concluída e depois remarcada para frente: volta a ficar pendente.
        assertFalse(TaskSchedule.isFinished(task(today.plusDays(7)), millis(today), zone))
        assertFalse(TaskSchedule.isFinished(due, null, zone))
        assertTrue(TaskSchedule.isFinished(task(null), millis(today.minusDays(30)), zone))
    }

    @Test
    fun `que se repete nunca se encerra e deixar de repetir reabre`() {
        val daily = task(today.minusDays(5), ReminderRepeat.DAILY)
        assertFalse(TaskSchedule.isFinished(daily, millis(today), zone))
        // Virou "não repete" para amanhã: as conclusões antigas não a encerram.
        assertFalse(TaskSchedule.isFinished(task(today.plusDays(1)), millis(today.minusDays(1)), zone))
    }

    @Test
    fun `acontece no dia so a partir da data`() {
        val daily = task(today, ReminderRepeat.DAILY)
        assertFalse(TaskSchedule.occursOn(daily, today.minusDays(1)))
        assertTrue(TaskSchedule.occursOn(daily, today.plusDays(5)))
        assertFalse(TaskSchedule.occursOn(task(null), today))
    }
}
