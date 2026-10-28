package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.CompletionLogDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.reminder.ReminderScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Fonte única das tarefas: mantém banco e alarmes de lembrete sempre em sincronia. */
class TaskRepository(
    private val taskDao: TaskDao,
    private val completionLogDao: CompletionLogDao,
    private val scheduler: ReminderScheduler,
) {

    val tasks: Flow<List<Task>> = taskDao.observeAll()

    /** Só as conclusões de hoje; troca de dia sozinho à meia-noite. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val completionsToday: Flow<List<CompletionLog>> =
        currentDate().flatMapLatest { today -> observeCompletionsBetween(today, today.plusDays(1)) }

    /** Última conclusão de cada tarefa (taskId → epoch millis): ver [TaskSchedule.isFinished]. */
    val lastCompletions: Flow<Map<Long, Long>> =
        completionLogDao.observeLastCompletions().map { rows -> rows.associate { it.taskId to it.completedAt } }

    /** Conclusões entre [start] (inclusive) e [endExclusive], pelo dia local. */
    fun observeCompletionsBetween(start: LocalDate, endExclusive: LocalDate): Flow<List<CompletionLog>> {
        val zone = ZoneId.systemDefault()
        return completionLogDao.observeBetween(
            start = start.atStartOfDay(zone).toInstant().toEpochMilli(),
            end = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    fun observeTask(id: Long): Flow<Task?> = taskDao.observeById(id)

    /** Conclusões da tarefa, da mais recente para a mais antiga. */
    fun observeCompletions(taskId: Long): Flow<List<CompletionLog>> = completionLogDao.observeForTask(taskId)

    suspend fun addTask(
        title: String,
        notes: String = "",
        timing: TaskTiming = TaskTiming(date = null),
        location: String = "",
    ): Task {
        val task = timing.applyTo(
            Task(title = title.trim(), notes = notes.trim(), location = location.trim()),
            ZoneId.systemDefault(),
        )
        val saved = task.copy(id = taskDao.insert(task))
        scheduler.schedule(saved)
        return saved
    }

    /**
     * Chamado quando um alarme toca: devolve a tarefa se a notificação deve aparecer e, se ela se
     * repete, já agenda a próxima ocorrência. `null` se foi excluída ou já está feita (a de hoje).
     */
    suspend fun onReminderFired(taskId: Long, now: Long = System.currentTimeMillis()): Task? {
        val task = taskDao.byId(taskId) ?: return null
        if (task.repeat != ReminderRepeat.NONE) {
            // A margem evita reagendar a mesma ocorrência que acabou de tocar.
            scheduler.schedule(task, after = now + FIRED_OCCURRENCE_MARGIN_MS)
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val doneToday = completionLogDao.isCompletedBetween(
                taskId = taskId,
                start = today.atStartOfDay(zone).toInstant().toEpochMilli(),
                end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            return task.takeUnless { doneToday }
        }
        return task.takeUnless { isFinished(task) }
    }

    /** Salva a edição e refaz o alarme: o lembrete pode ter mudado, sido removido ou já ter passado. */
    suspend fun updateTask(task: Task) {
        taskDao.update(task)
        scheduler.cancel(task.id)
        if (!isFinished(task)) scheduler.schedule(task)
    }

    suspend fun deleteTask(task: Task) {
        scheduler.cancel(task.id)
        taskDao.delete(task)
    }

    /** Reinsere uma tarefa excluída com o mesmo id, reconectando-a ao histórico de conclusões. */
    suspend fun restoreTask(task: Task) {
        taskDao.insert(task)
        if (!isFinished(task)) scheduler.schedule(task)
    }

    /**
     * Devolve o id do evento, usado para desfazer a conclusão. Uma tarefa sem repetição fica
     * encerrada: o lembrete dela, se ainda não tocou, é cancelado. Concluída antes do dia dela,
     * ela passa a ser de hoje (foi feita hoje) e perde o lembrete futuro.
     */
    suspend fun logCompletion(taskId: Long, completedAt: Long = System.currentTimeMillis()): Long {
        val task = taskDao.byId(taskId)
        if (task != null && task.repeat == ReminderRepeat.NONE) {
            scheduler.cancel(taskId)
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate().toEpochDay()
            if (task.dueDate != null && task.dueDate > day) {
                taskDao.update(task.copy(dueDate = day, reminderAt = null))
            }
        }
        return completionLogDao.insert(CompletionLog(taskId = taskId, completedAt = completedAt))
    }

    /** Desfaz uma conclusão; a tarefa volta a ficar pendente e o lembrete, se ainda vale, volta. */
    suspend fun undoCompletion(logId: Long) {
        val log = completionLogDao.byId(logId) ?: return
        completionLogDao.deleteById(logId)
        val task = taskDao.byId(log.taskId) ?: return
        if (!isFinished(task)) scheduler.schedule(task)
    }

    /** Refaz todos os alarmes (reinício, atualização do app, mudança de fuso); chamado pelo BootReceiver. */
    suspend fun rescheduleReminders(now: Long = System.currentTimeMillis()) {
        val zone = ZoneId.systemDefault()
        val last = completionLogDao.lastCompletions().associate { it.taskId to it.completedAt }
        scheduler.rescheduleAll(
            taskDao.withPendingReminder(now).filterNot { TaskSchedule.isFinished(it, last[it.id], zone) },
        )
    }

    /** Sem repetição e já concluída (no dia dela ou depois): não lembra mais. */
    private suspend fun isFinished(task: Task): Boolean =
        TaskSchedule.isFinished(task, completionLogDao.lastCompletion(task.id), ZoneId.systemDefault())

    private companion object {
        const val FIRED_OCCURRENCE_MARGIN_MS = 60_000L
    }
}
