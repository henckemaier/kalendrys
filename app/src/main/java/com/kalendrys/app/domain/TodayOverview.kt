package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** O que importa hoje: base do resumo das 8h e dos widgets. */
data class TodayOverview(
    val date: LocalDate,
    /** Tarefas para hoje (atrasadas, do dia e sem data): primeiro as com lembrete hoje, por horário. */
    val pending: List<PendingTask>,
    /** Tarefas feitas hoje, pelo registro de conclusão de hoje ([doneTodayIds]). */
    val doneCount: Int,
    /** Blocos de rotina de hoje, em ordem de horário, feitos ou não. */
    val routines: List<RoutineBlock> = emptyList(),
    /**
     * O que vem a seguir: o próximo lembrete de tarefa ou início de rotina ainda não feita, mesmo
     * que seja em outro dia. É também quando este retrato muda só pela passagem do tempo.
     */
    val nextUp: Upcoming? = null,
) {
    data class PendingTask(
        val taskId: Long,
        val title: String,
        /** Horário em que o lembrete toca hoje; null se não toca hoje. */
        val reminderToday: LocalTime?,
        val recurring: Boolean,
        /** Sem repetição e com a data já passada. */
        val overdue: Boolean = false,
        /** Tem data (é de hoje ou atrasada); as sem data também aparecem como pendentes. */
        val dated: Boolean = true,
        /** Progresso da lista de compras anexada (ADR-0001, P8); null sem lista ou com vínculo órfão. */
        val listProgress: ShoppingProgress? = null,
    )

    data class RoutineBlock(
        val routineId: Long,
        val title: String,
        val start: LocalTime,
        /** null quando o bloco não tem duração. */
        val end: LocalTime?,
        val color: RoutineColor,
        val done: Boolean,
        /** Progresso da lista de compras anexada (ADR-0001, P8); null sem lista ou com vínculo órfão. */
        val listProgress: ShoppingProgress? = null,
    )

    data class Upcoming(val title: String, val at: LocalDateTime, val isRoutine: Boolean)

    val remindersToday: Int get() = pending.count { it.reminderToday != null }

    val pendingRoutines: List<RoutineBlock> get() = routines.filterNot { it.done }

    /** Tarefas e blocos de rotina que ainda faltam hoje. */
    val pendingCount: Int get() = pending.size + pendingRoutines.size

    companion object {
        fun build(
            tasks: List<Task>,
            completions: List<CompletionLog>,
            now: LocalDateTime,
            zone: ZoneId,
            routines: List<Routine> = emptyList(),
            routineCompletions: List<RoutineCompletion> = emptyList(),
            /** Última conclusão de cada tarefa, em qualquer dia (decide se as sem repetição estão encerradas). */
            lastCompletions: Map<Long, Long> = emptyMap(),
            /** Exceções por rotina (T20): um `SKIP` some do retrato e um `RESCHEDULE` muda o dia/horário. */
            overrides: Map<Long, List<RoutineOverride>> = emptyMap(),
            /**
             * Progresso de cada lista de compras EXISTENTE (listId → progresso). Só entra no retrato a
             * lista de uma tarefa pendente ou de um bloco de hoje; lista avulsa fica de fora, e um
             * vínculo para lista ausente deste mapa (órfão) fica sem progresso.
             */
            listProgress: Map<Long, ShoppingProgress> = emptyMap(),
        ): TodayOverview {
            val today = now.toLocalDate()
            val doneToday = doneTodayIds(tasks, completions, today, zone)
            val routinesDoneToday = routineCompletions
                .filter { it.epochDay == today.toEpochDay() }
                .mapTo(HashSet()) { it.routineId }

            val statuses = tasks.associate { task ->
                task.id to TaskSchedule.status(
                    task = task,
                    today = today,
                    doneEver = TaskSchedule.isFinished(task, lastCompletions[task.id], zone),
                    doneToday = task.id in doneToday,
                )
            }
            // Pendentes de hoje: atrasadas, as de hoje e as sem data (as futuras ficam de fora).
            val pending = tasks
                .filter { statuses[it.id] in PENDING_TODAY }
                .map { task ->
                    PendingTask(
                        taskId = task.id,
                        title = task.title,
                        reminderToday = TaskSchedule.reminderOn(task, today, zone),
                        recurring = task.repeat != ReminderRepeat.NONE,
                        overdue = statuses[task.id] == TaskSchedule.Status.OVERDUE,
                        dated = task.dueDate != null,
                        listProgress = task.shoppingListId?.let(listProgress::get),
                    )
                }
                // Primeiro as com horário hoje (em ordem), depois atrasadas, as de hoje e as sem data.
                .sortedWith(
                    compareBy<PendingTask, LocalTime?>(nullsLast()) { it.reminderToday }
                        .thenBy { statuses[it.taskId]?.let(PENDING_TODAY::indexOf) },
                )

            val routineBlocks = routines
                .mapNotNull { routine ->
                    val ov = overrides[routine.id].orEmpty()
                    if (!RoutineSchedule.occursOn(routine, today, zone, ov)) return@mapNotNull null
                    val start = RoutineSchedule.startOn(routine, today, ov)
                    val startMinute = start.hour * 60 + start.minute
                    RoutineBlock(
                        routineId = routine.id,
                        title = routine.title,
                        start = start.toLocalTime(),
                        end = routine.takeIf { it.durationMinutes > 0 }?.let { timeOfDay(startMinute + it.durationMinutes) },
                        color = RoutineColor.fromKey(routine.color),
                        done = routine.id in routinesDoneToday,
                        // Rotina arquivada mantém o vínculo, mas ele é ignorado na leitura (P5).
                        listProgress = routine.shoppingListId
                            ?.takeIf { routine.archivedAt == null }
                            ?.let(listProgress::get),
                    )
                }
                .sortedWith(compareBy({ it.start }, { it.routineId }))

            // Tarefa ou bloco de hoje já feito não é mais "a seguir": vale a próxima ocorrência.
            val endOfToday = today.plusDays(1).atStartOfDay().minusNanos(1)
            val nextTaskReminder = tasks
                // Sem repetição e encerrada (inclusive hoje): o lembrete foi cancelado.
                .filterNot { TaskSchedule.isFinished(it, lastCompletions[it.id], zone) }
                .mapNotNull { task ->
                    val anchor = task.anchor(zone) ?: return@mapNotNull null
                    val next = ReminderRecurrence.nextOccurrence(
                        anchor, task.repeat, now, task.intervalEvery, task.intervalUnit, task.byWeekdays,
                    ) ?: return@mapNotNull null
                    val at = when {
                        next.toLocalDate() != today || task.id !in doneToday -> next
                        task.repeat == ReminderRepeat.NONE -> return@mapNotNull null
                        else -> ReminderRecurrence.nextOccurrence(
                            anchor, task.repeat, endOfToday, task.intervalEvery, task.intervalUnit, task.byWeekdays,
                        ) ?: return@mapNotNull null
                    }
                    Upcoming(task.title, at, isRoutine = false)
                }
                .minByOrNull { it.at }
            val nextRoutine = routines
                .mapNotNull { routine ->
                    val ov = overrides[routine.id].orEmpty()
                    val next = RoutineSchedule.nextStart(routine, now, zone, ov) ?: return@mapNotNull null
                    val at = if (next.toLocalDate() == today && routine.id in routinesDoneToday) {
                        RoutineSchedule.nextStart(routine, endOfToday, zone, ov) ?: return@mapNotNull null
                    } else {
                        next
                    }
                    Upcoming(routine.title, at, isRoutine = true)
                }
                .minByOrNull { it.at }

            return TodayOverview(
                date = today,
                pending = pending,
                // Pelo REGISTRO de hoje, não pelo estado (NB-E3-A): a mesma conta das Concluídas da Hoje.
                doneCount = doneToday.size,
                routines = routineBlocks,
                nextUp = listOfNotNull(nextTaskReminder, nextRoutine).minByOrNull { it.at },
            )
        }

        /**
         * Feitas hoje (NB-E3-A), a definição ÚNICA da Hoje, do widget Resumo e do resumo diário:
         * as tarefas que EXISTEM em [tasks] e têm registro de conclusão em [today] (no [zone]), cada
         * uma contada uma vez. Vale qualquer tarefa concluída hoje, mesmo uma repetida de outro dia
         * ou uma reaberta para outra data; excluída não conta (não está em [tasks]); duas conclusões
         * no dia contam uma; registro gravado em outro dia (conclusão retroativa) não conta hoje.
         */
        fun doneTodayIds(tasks: List<Task>, completions: List<CompletionLog>, today: LocalDate, zone: ZoneId): Set<Long> {
            val existing = tasks.mapTo(HashSet()) { it.id }
            return completions
                .filter { it.taskId in existing && Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == today }
                .mapTo(HashSet()) { it.taskId }
        }

        private val PENDING_TODAY = listOf(
            TaskSchedule.Status.OVERDUE,
            TaskSchedule.Status.TODAY,
            TaskSchedule.Status.NO_DATE,
        )

        private fun timeOfDay(minute: Int): LocalTime = LocalTime.of(minute % 1440 / 60, minute % 60)

        private fun Task.anchor(zone: ZoneId): LocalDateTime? =
            reminderAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    }
}
