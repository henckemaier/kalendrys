package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
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
    fun `resumo honra excecoes de ocorrencia`() {
        val today = now.toLocalDate()
        val routines = listOf(
            routine(1, "Treino", LocalTime.of(7, 0)),
            routine(2, "Almoço", LocalTime.of(12, 0)),
        )
        val overrides = mapOf(
            1L to listOf(RoutineOverride(routineId = 1, epochDay = today.toEpochDay(), type = RoutineOverrideType.SKIP)),
            2L to listOf(
                RoutineOverride(
                    routineId = 2,
                    epochDay = today.toEpochDay(),
                    type = RoutineOverrideType.RESCHEDULE,
                    newStartMinute = 15 * 60,
                ),
            ),
        )

        val overview = TodayOverview.build(emptyList(), emptyList(), now, zone, routines, overrides = overrides)

        // O SKIP (Treino) some do retrato; a remarcada (Almoço) aparece no novo horário.
        assertEquals(listOf(2L), overview.routines.map { it.routineId })
        assertEquals(LocalTime.of(15, 0), overview.routines.single().start)
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

    // --- Progresso das listas no retrato do dia (ADR-0001, P8) ---

    @Test
    fun `tarefa e bloco com lista trazem o progresso, avulsa fica de fora e orfao sem progresso`() {
        val progress = mapOf(10L to ShoppingProgress(3, 8), 11L to ShoppingProgress(1, 2), 12L to ShoppingProgress(0, 4))
        val tasks = listOf(
            task(1, "Com lista", reminder = now.plusHours(2)).copy(shoppingListId = 10),
            task(2, "Órfã", reminder = now.plusHours(3)).copy(shoppingListId = 99),
            task(3, "Sem lista", reminder = now.plusHours(4)),
        )
        val routines = listOf(
            routine(5, "Feira", LocalTime.of(18, 0)).copy(shoppingListId = 11),
            routine(6, "Arquivada", LocalTime.of(19, 0)).copy(shoppingListId = 11, archivedAt = millis(now.plusDays(1))),
        )

        val overview = TodayOverview.build(tasks, emptyList(), now, zone, routines, listProgress = progress)

        assertEquals(
            listOf(ShoppingProgress(3, 8), null, null),
            overview.pending.sortedBy { it.taskId }.map { it.listProgress },
        )
        assertEquals(ShoppingProgress(1, 2), overview.routines.first { it.routineId == 5L }.listProgress)
        assertNull("rotina arquivada ignora o vínculo", overview.routines.firstOrNull { it.routineId == 6L }?.listProgress)
        // A lista 12 é avulsa: não aparece em lugar nenhum do retrato.
        val shown = overview.pending.mapNotNull { it.listProgress } + overview.routines.mapNotNull { it.listProgress }
        assertTrue(ShoppingProgress(0, 4) !in shown)
    }

    // --- NB-E3-A: feitas hoje = tarefa que existe com registro de conclusão hoje, contada uma vez ---

    private fun doneCount(tasks: List<Task>, logs: List<CompletionLog>) = TodayOverview.build(
        tasks = tasks,
        completions = logs,
        now = now,
        zone = zone,
        lastCompletions = logs.groupBy { it.taskId }.mapValues { (_, l) -> l.maxOf { it.completedAt } },
    ).doneCount

    @Test
    fun `repetida de amanha concluida hoje conta 1`() {
        val weekly = task(1, "Tirar o lixo", reminder = now.plusDays(1).withHour(19), repeat = ReminderRepeat.WEEKLY)

        assertEquals(1, doneCount(listOf(weekly), listOf(done(1, now.minusHours(1)))))
    }

    @Test
    fun `unica reaberta para outra data depois de concluida hoje conta 1`() {
        // Concluída hoje e depois com a data mudada para daqui a 3 dias (reabre: não está encerrada).
        val reopened = Task(id = 1, title = "Levar o carro", dueDate = now.toLocalDate().plusDays(3).toEpochDay())

        assertEquals(1, doneCount(listOf(reopened), listOf(done(1, now.minusHours(1)))))
    }

    @Test
    fun `excluida conta 0 e duas conclusoes hoje contam 1`() {
        val daily = task(1, "Remédio", reminder = now.minusDays(5).withHour(7), repeat = ReminderRepeat.DAILY)
        // A tarefa 2 foi excluída: o registro dela fica no histórico, mas ela não existe mais.
        val logs = listOf(done(1, now.minusHours(2)), done(1, now.minusHours(1)), done(2, now.minusHours(1)))

        assertEquals(1, doneCount(listOf(daily), logs))
        assertEquals(setOf(1L), TodayOverview.doneTodayIds(listOf(daily), logs, now.toLocalDate(), zone))
    }

    @Test
    fun `registro gravado em ontem (conclusao retroativa) nao conta hoje, o de hoje conta`() {
        val tasks = listOf(
            task(1, "Ontem", reminder = now.minusDays(5).withHour(7), repeat = ReminderRepeat.DAILY),
            task(2, "Hoje", reminder = now.minusDays(5).withHour(7), repeat = ReminderRepeat.DAILY),
        )
        // A 1 foi marcada na Hoje com o dia de ontem selecionado: o registro fica no dia de ontem.
        val logs = listOf(done(1, now.minusDays(1).withHour(12)), done(2, now.minusHours(1)))

        assertEquals(setOf(2L), TodayOverview.doneTodayIds(tasks, logs, now.toLocalDate(), zone))
        assertEquals(1, doneCount(tasks, logs))
    }
}
