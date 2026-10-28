package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayOverviewTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    // Sábado, 24/10/2026, 08:00 — o horário do resumo.
    private val now = LocalDateTime.of(2026, 10, 24, 8, 0)

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private fun task(id: Long, title: String, reminder: LocalDateTime? = null, repeat: ReminderRepeat = ReminderRepeat.NONE) =
        Task(
            id = id,
            title = title,
            dueDate = reminder?.toLocalDate()?.toEpochDay(),
            reminderAt = reminder?.let(::millis),
            repeat = repeat,
        )

    private fun done(taskId: Long, at: LocalDateTime) = CompletionLog(taskId = taskId, completedAt = millis(at))

    @Test
    fun `tarefa sem repeticao concluida nao volta e a que se repete volta no dia seguinte`() {
        val tasks = listOf(
            task(1, "Ler e-mails"),
            task(2, "Pagar conta"),
            task(3, "Treino", reminder = now.minusDays(5).withHour(18), repeat = ReminderRepeat.DAILY),
        )
        val logs = listOf(done(1, now.minusHours(1)), done(2, now.minusDays(1)), done(3, now.minusDays(1)))

        val overview = TodayOverview.build(
            tasks = tasks,
            completions = logs,
            now = now,
            zone = zone,
            lastCompletions = logs.associate { it.taskId to it.completedAt },
        )

        assertEquals(listOf(3L), overview.pending.map { it.taskId })
        assertEquals(1, overview.doneCount)
    }

    @Test
    fun `atrasada entra nas pendentes e a futura nao`() {
        val tasks = listOf(
            Task(id = 1, title = "Ontem", dueDate = now.toLocalDate().minusDays(1).toEpochDay()),
            Task(id = 2, title = "Amanhã", dueDate = now.toLocalDate().plusDays(1).toEpochDay()),
        )

        val overview = TodayOverview.build(tasks, emptyList(), now, zone)

        assertEquals(listOf(1L), overview.pending.map { it.taskId })
        assertTrue(overview.pending.single().overdue)
    }

    @Test
    fun `pendentes com lembrete hoje vem primeiro por horario e as demais mantem a ordem`() {
        val tasks = listOf(
            task(1, "Sem lembrete A"),
            task(2, "Às 15h", reminder = now.withHour(15)),
            task(3, "Sem lembrete B"),
            task(4, "Às 9h", reminder = now.withHour(9)),
            task(5, "Amanhã", reminder = now.plusDays(1)),
        )

        val overview = TodayOverview.build(tasks, emptyList(), now, zone)

        // A de amanhã fica de fora; as sem data vêm depois das com horário.
        assertEquals(listOf(4L, 2L, 1L, 3L), overview.pending.map { it.taskId })
        assertEquals(2, overview.remindersToday)
    }

    @Test
    fun `lembrete recorrente conta como lembrete de hoje quando ocorre hoje`() {
        val tasks = listOf(
            task(1, "Remédio", reminder = now.minusDays(10).withHour(21), repeat = ReminderRepeat.DAILY),
            // Dias úteis não ocorrem no sábado.
            task(2, "Ponto", reminder = now.minusDays(10).withHour(9), repeat = ReminderRepeat.WEEKDAYS),
        )

        val overview = TodayOverview.build(tasks, emptyList(), now, zone)

        assertEquals(LocalTime.of(21, 0), overview.pending.first { it.taskId == 1L }.reminderToday)
        assertTrue(overview.pending.none { it.taskId == 2L })
    }

    @Test
    fun `proximo lembrete e a ocorrencia mais proxima entre todas as tarefas`() {
        val tasks = listOf(
            task(1, "Semanal", reminder = now.minusDays(3).withHour(10), repeat = ReminderRepeat.WEEKLY),
            task(2, "Hoje às 18h", reminder = now.withHour(18)),
            task(3, "Já passou", reminder = now.minusHours(2)),
        )

        val overview = TodayOverview.build(tasks, emptyList(), now, zone)

        assertEquals("Hoje às 18h", overview.nextUp?.title)
        assertEquals(now.withHour(18), overview.nextUp?.at)
    }

    @Test
    fun `sem lembretes futuros nao ha proximo lembrete`() {
        val overview = TodayOverview.build(listOf(task(1, "Antigo", reminder = now.minusDays(1))), emptyList(), now, zone)
        assertNull(overview.nextUp)
    }

    private fun routine(id: Long, title: String, start: LocalTime, minutes: Int = 30, days: Int = RoutineDays.ALL) =
        Routine(
            id = id,
            title = title,
            startMinute = start.hour * 60 + start.minute,
            durationMinutes = minutes,
            daysOfWeek = days,
            createdAt = millis(now.minusDays(30)),
        )

    @Test
    fun `rotinas de hoje aparecem em ordem com o estado de feita`() {
        val routines = listOf(
            routine(1, "Leitura", LocalTime.of(21, 0)),
            routine(2, "Treino", LocalTime.of(7, 0)),
            // Dias úteis não ocorrem no sábado.
            routine(3, "Ponto", LocalTime.of(9, 0), days = RoutineDays.WEEKDAYS),
        )
        val doneToday = listOf(RoutineCompletion(routineId = 2, epochDay = now.toLocalDate().toEpochDay()))

        val overview = TodayOverview.build(emptyList(), emptyList(), now, zone, routines, doneToday)

        assertEquals(listOf(2L, 1L), overview.routines.map { it.routineId })
        assertEquals(listOf(true, false), overview.routines.map { it.done })
        assertEquals(LocalTime.of(7, 30), overview.routines.first().end)
        assertEquals(1, overview.pendingCount)
    }

    @Test
    fun `a seguir e o mais cedo entre lembrete de tarefa e inicio de rotina`() {
        val tasks = listOf(task(1, "Ligar", reminder = now.withHour(18)))
        val routines = listOf(routine(1, "Almoço", LocalTime.of(12, 0)))

        val overview = TodayOverview.build(tasks, emptyList(), now, zone, routines)

        assertEquals(TodayOverview.Upcoming("Almoço", now.withHour(12), isRoutine = true), overview.nextUp)
    }

    @Test
    fun `rotina ja feita hoje passa para a proxima ocorrencia no a seguir`() {
        val routines = listOf(routine(1, "Almoço", LocalTime.of(12, 0)))
        val doneToday = listOf(RoutineCompletion(routineId = 1, epochDay = now.toLocalDate().toEpochDay()))

        val overview = TodayOverview.build(emptyList(), emptyList(), now, zone, routines, doneToday)

        assertEquals(now.plusDays(1).withHour(12), overview.nextUp?.at)
    }
}
