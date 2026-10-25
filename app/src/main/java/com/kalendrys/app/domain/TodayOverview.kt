package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** O que importa hoje: base do resumo das 8h e dos widgets. */
data class TodayOverview(
    val date: LocalDate,
    /** Tarefas ainda não concluídas hoje: primeiro as com lembrete hoje (por horário), depois as demais. */
    val pending: List<PendingTask>,
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
    )

    data class RoutineBlock(
        val routineId: Long,
        val title: String,
        val start: LocalTime,
        /** null quando o bloco não tem duração. */
        val end: LocalTime?,
        val color: RoutineColor,
        val done: Boolean,
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
        ): TodayOverview {
            val today = now.toLocalDate()
            val doneToday = completions
                .filter { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == today }
                .mapTo(HashSet()) { it.taskId }
            val routinesDoneToday = routineCompletions
                .filter { it.epochDay == today.toEpochDay() }
                .mapTo(HashSet()) { it.routineId }

            val pending = tasks
                .filter { it.id !in doneToday }
                .map { task ->
                    val anchor = task.anchor(zone)
                    PendingTask(
                        taskId = task.id,
                        title = task.title,
                        reminderToday = anchor
                            ?.takeIf { ReminderRecurrence.occursOn(today, it, task.reminderRepeat) }
                            ?.toLocalTime(),
                        recurring = task.reminderRepeat != ReminderRepeat.NONE,
                    )
                }
                // sortedWith é estável: as tarefas sem lembrete hoje mantêm a ordem da lista.
                .sortedWith(compareBy(nullsLast()) { it.reminderToday })

            val routineBlocks = routines
                .filter { RoutineSchedule.occursOn(it, today, zone) }
                .sortedWith(compareBy({ it.startMinute }, { it.id }))
                .map { routine ->
                    RoutineBlock(
                        routineId = routine.id,
                        title = routine.title,
                        start = timeOfDay(routine.startMinute),
                        end = routine.takeIf { it.durationMinutes > 0 }?.let { timeOfDay(it.endMinute) },
                        color = RoutineColor.fromKey(routine.color),
                        done = routine.id in routinesDoneToday,
                    )
                }

            val nextTaskReminder = tasks
                .mapNotNull { task ->
                    val anchor = task.anchor(zone) ?: return@mapNotNull null
                    ReminderRecurrence.nextOccurrence(anchor, task.reminderRepeat, now)
                        ?.let { Upcoming(task.title, it, isRoutine = false) }
                }
                .minByOrNull { it.at }
            // Um bloco de hoje já feito não é mais "a seguir": vale a próxima ocorrência dele.
            val endOfToday = today.plusDays(1).atStartOfDay().minusNanos(1)
            val nextRoutine = routines
                .mapNotNull { routine ->
                    val next = RoutineSchedule.nextStart(routine, now, zone) ?: return@mapNotNull null
                    val at = if (next.toLocalDate() == today && routine.id in routinesDoneToday) {
                        RoutineSchedule.nextStart(routine, endOfToday, zone) ?: return@mapNotNull null
                    } else {
                        next
                    }
                    Upcoming(routine.title, at, isRoutine = true)
                }
                .minByOrNull { it.at }

            return TodayOverview(
                date = today,
                pending = pending,
                doneCount = tasks.count { it.id in doneToday },
                routines = routineBlocks,
                nextUp = listOfNotNull(nextTaskReminder, nextRoutine).minByOrNull { it.at },
            )
        }

        private fun timeOfDay(minute: Int): LocalTime = LocalTime.of(minute % 1440 / 60, minute % 60)

        private fun Task.anchor(zone: ZoneId): LocalDateTime? =
            reminderAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
    }
}
