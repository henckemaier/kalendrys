package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.CompletionLogDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.reminder.ReminderScheduler
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

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

    /**
     * Um lembrete único que já passou é descartado (não teria alarme para tocar); um recorrente
     * com início no passado continua valendo a partir da próxima ocorrência.
     */
    suspend fun addTask(
        title: String,
        notes: String = "",
        reminderAt: Long? = null,
        reminderRepeat: ReminderRepeat = ReminderRepeat.NONE,
    ): Task {
        val keptReminder = reminderAt?.takeIf {
            reminderRepeat != ReminderRepeat.NONE || it > System.currentTimeMillis()
        }
        val task = Task(
            title = title.trim(),
            notes = notes.trim(),
            reminderAt = keptReminder,
            reminderRepeat = if (keptReminder == null) ReminderRepeat.NONE else reminderRepeat,
        )
        val saved = task.copy(id = taskDao.insert(task))
        scheduler.schedule(saved)
        return saved
    }

    /**
     * Chamado quando um alarme toca: devolve a tarefa (para a notificação) e, se o lembrete é
     * recorrente, já agenda a próxima ocorrência. `null` se a tarefa foi excluída.
     */
    suspend fun onReminderFired(taskId: Long, now: Long = System.currentTimeMillis()): Task? {
        val task = taskDao.byId(taskId) ?: return null
        if (task.reminderRepeat != ReminderRepeat.NONE) {
            // A margem evita reagendar a mesma ocorrência que acabou de tocar.
            scheduler.schedule(task, after = now + FIRED_OCCURRENCE_MARGIN_MS)
        }
        return task
    }

    /** Salva a edição e refaz o alarme: o lembrete pode ter mudado, sido removido ou já ter passado. */
    suspend fun updateTask(task: Task) {
        taskDao.update(task)
        scheduler.cancel(task.id)
        scheduler.schedule(task)
    }

    suspend fun deleteTask(task: Task) {
        scheduler.cancel(task.id)
        taskDao.delete(task)
    }

    /** Reinsere uma tarefa excluída com o mesmo id, reconectando-a ao histórico de conclusões. */
    suspend fun restoreTask(task: Task) {
        taskDao.insert(task)
        scheduler.schedule(task)
    }

    /** Devolve o id do evento, usado para desfazer a conclusão. */
    suspend fun logCompletion(taskId: Long, completedAt: Long = System.currentTimeMillis()): Long =
        completionLogDao.insert(CompletionLog(taskId = taskId, completedAt = completedAt))

    suspend fun undoCompletion(logId: Long) = completionLogDao.deleteById(logId)

    /** Refaz todos os alarmes (reinício, atualização do app, mudança de fuso); chamado pelo BootReceiver. */
    suspend fun rescheduleReminders(now: Long = System.currentTimeMillis()) {
        scheduler.rescheduleAll(taskDao.withPendingReminder(now))
    }

    private companion object {
        const val FIRED_OCCURRENCE_MARGIN_MS = 60_000L
    }
}
