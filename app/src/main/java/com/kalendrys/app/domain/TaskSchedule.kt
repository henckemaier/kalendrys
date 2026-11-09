package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Data, horário do lembrete e repetição de uma tarefa, como o formulário os edita. No banco
 * viram `dueDate` + `reminderAt` (o horário nesse dia) + `repeat`; repetir exige uma data.
 */
data class TaskTiming(
    val date: LocalDate?,
    val time: LocalTime? = null,
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    /** Configuração da recorrência [ReminderRepeat.CUSTOM]; ver [com.kalendrys.app.data.local.entity.Task]. */
    val intervalEvery: Int = 1,
    val intervalUnit: RecurrenceUnit? = null,
    val byWeekdays: Int = 0,
) {
    val effectiveRepeat: ReminderRepeat get() = if (date == null) ReminderRepeat.NONE else repeat

    fun reminderAt(zone: ZoneId): Long? =
        if (date != null && time != null) date.atTime(time).atZone(zone).toInstant().toEpochMilli() else null

    fun applyTo(task: Task, zone: ZoneId): Task =
        task.copy(
            dueDate = date?.toEpochDay(),
            reminderAt = reminderAt(zone),
            repeat = effectiveRepeat,
            intervalEvery = intervalEvery.coerceAtLeast(1),
            intervalUnit = intervalUnit,
            byWeekdays = byWeekdays,
        )

    companion object {
        fun of(task: Task, zone: ZoneId) = TaskTiming(
            date = task.dueDate?.let(LocalDate::ofEpochDay),
            time = task.reminderAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() },
            repeat = task.repeat,
            intervalEvery = task.intervalEvery,
            intervalUnit = task.intervalUnit,
            byWeekdays = task.byWeekdays,
        )
    }
}

/** Em que dias cada tarefa acontece e como ela está num dia. Lógica pura, testada na JVM. */
object TaskSchedule {

    enum class Status {
        /** Sem repetição, com data já passada e ainda não concluída. */
        OVERDUE,

        /** Acontece hoje e ainda não foi concluída hoje. */
        TODAY,

        /** Só acontece num dia futuro. */
        UPCOMING,

        /** Sem data e ainda não concluída. */
        NO_DATE,

        /** Concluída hoje (a de hoje, numa tarefa que se repete). */
        DONE_TODAY,

        /** Sem repetição e concluída num dia anterior: não aparece mais na lista. */
        DONE,
    }

    fun dueDate(task: Task): LocalDate? = task.dueDate?.let(LocalDate::ofEpochDay)

    fun occursOn(task: Task, date: LocalDate): Boolean {
        val start = dueDate(task) ?: return false
        return ReminderRecurrence.occursOn(
            date,
            start.atStartOfDay(),
            task.repeat,
            task.intervalEvery,
            task.intervalUnit,
            task.byWeekdays,
        )
    }

    /** Primeiro dia com a tarefa a partir de [from] (inclusive); `null` se não há mais nenhum. */
    fun nextOccurrence(task: Task, from: LocalDate): LocalDate? {
        val start = dueDate(task) ?: return null
        // Estritamente depois do último instante do dia anterior = a partir de [from].
        return ReminderRecurrence.nextOccurrence(
            start.atStartOfDay(),
            task.repeat,
            from.atStartOfDay().minusNanos(1),
            task.intervalEvery,
            task.intervalUnit,
            task.byWeekdays,
        )?.toLocalDate()
    }

    /**
     * Uma tarefa sem repetição está encerrada se foi concluída no dia dela ou depois (sem data:
     * qualquer conclusão). Assim, mudar a data para frente — ou deixar de repetir — a reabre,
     * sem apagar o histórico. Tarefas que se repetem nunca se encerram.
     */
    fun isFinished(task: Task, lastCompletedAt: Long?, zone: ZoneId): Boolean {
        if (task.repeat != ReminderRepeat.NONE && task.dueDate != null) return false
        val last = lastCompletedAt ?: return false
        val due = dueDate(task) ?: return true
        return !Instant.ofEpochMilli(last).atZone(zone).toLocalDate().isBefore(due)
    }

    /**
     * [doneEver]: a tarefa está encerrada ([isFinished]; só vale para as sem repetição);
     * [doneToday]: alguma conclusão hoje.
     */
    fun status(task: Task, today: LocalDate, doneEver: Boolean, doneToday: Boolean): Status {
        if (task.repeat != ReminderRepeat.NONE && task.dueDate != null) {
            return when {
                occursOn(task, today) -> if (doneToday) Status.DONE_TODAY else Status.TODAY
                else -> Status.UPCOMING
            }
        }
        if (doneEver) return if (doneToday) Status.DONE_TODAY else Status.DONE
        val due = dueDate(task) ?: return Status.NO_DATE
        return when {
            due.isBefore(today) -> Status.OVERDUE
            due == today -> Status.TODAY
            else -> Status.UPCOMING
        }
    }

    /** Horário do lembrete no dia [date], se a tarefa tem lembrete e acontece nesse dia. */
    fun reminderOn(task: Task, date: LocalDate, zone: ZoneId): LocalTime? {
        val reminderAt = task.reminderAt ?: return null
        if (!occursOn(task, date)) return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), zone).toLocalTime()
    }
}
