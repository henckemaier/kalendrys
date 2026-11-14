package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.CompletionLogDao
import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.ShoppingCompletionOffer
import com.kalendrys.app.domain.ShoppingReset
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
    private val shoppingItemDao: ShoppingItemDao,
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

    /**
     * Tarefa para reexibir num adiamento ("Adiar"): a versão atual do banco, ou `null` se ela foi
     * excluída, encerrada ou (recorrente) já concluída hoje. Só lê — não mexe em alarmes nem no
     * banco; o alarme one-off do adiamento é agendado fora, pelo próprio receiver.
     */
    suspend fun taskForReminder(taskId: Long, now: Long = System.currentTimeMillis()): Task? {
        val task = taskDao.byId(taskId) ?: return null
        if (task.repeat == ReminderRepeat.NONE) return task.takeUnless { isFinished(task) }
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val doneToday = completionLogDao.isCompletedBetween(
            taskId = taskId,
            start = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        return task.takeUnless { doneToday }
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

    /**
     * Reinsere uma tarefa excluída com o mesmo id, reconectando-a ao histórico de conclusões. O
     * vínculo com a lista de compras ([Task.shoppingListId]) volta junto (ADR-0001, P5): excluir a
     * tarefa nunca apagou a lista.
     */
    suspend fun restoreTask(task: Task) {
        taskDao.insert(task)
        if (!isFinished(task)) scheduler.schedule(task)
    }

    /**
     * Devolve o id do evento, usado para desfazer a conclusão. Uma tarefa sem repetição fica
     * encerrada: o lembrete dela, se ainda não tocou, é cancelado. Concluída antes do dia dela,
     * ela passa a ser de hoje (foi feita hoje) e perde o lembrete futuro.
     *
     * Tarefa que se REPETE com lista anexada zera as marcações da lista (ADR-0001, P4), de forma
     * reversível pelo [undoCompletion]. Ordem: grava o log e depois reinicia com o id dele (uma falha
     * entre os dois deixa a conclusão sem reinício, o que é inofensivo). Tarefa única mantém as
     * marcações. Mora aqui, e não na tela, para valer também no widget e na notificação.
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
        val logId = completionLogDao.insert(CompletionLog(taskId = taskId, completedAt = completedAt))
        val listId = task?.shoppingListId
        if (listId != null && ShoppingReset.taskResets(task.repeat)) {
            shoppingItemDao.resetPicked(listId, ShoppingReset.taskKey(logId))
        }
        return logId
    }

    /**
     * Desfaz uma conclusão; a tarefa volta a ficar pendente e o lembrete, se ainda vale, volta. As
     * marcações que essa conclusão tinha zerado voltam (se ainda for o último reinício da lista).
     */
    suspend fun undoCompletion(logId: Long) {
        val log = completionLogDao.byId(logId) ?: return
        completionLogDao.deleteById(logId)
        shoppingItemDao.restoreReset(ShoppingReset.taskKey(logId))
        val task = taskDao.byId(log.taskId) ?: return
        if (!isFinished(task)) scheduler.schedule(task)
    }

    /**
     * Tarefas vinculadas à lista [listId] e se cada uma está pendente agora (atrasada, de hoje ou
     * sem data, ainda não concluída), para a oferta "Concluir tarefa" ([ShoppingCompletionOffer]).
     */
    suspend fun linkedTasks(
        listId: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ShoppingCompletionOffer.LinkedTask> {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return taskDao.byShoppingList(listId).map { task ->
            val status = TaskSchedule.status(
                task = task,
                today = today,
                doneEver = TaskSchedule.isFinished(task, completionLogDao.lastCompletion(task.id), zone),
                doneToday = completionLogDao.isCompletedBetween(task.id, start, end),
            )
            ShoppingCompletionOffer.LinkedTask(task.id, pending = status in ShoppingCompletionOffer.PENDING_STATUSES)
        }
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
