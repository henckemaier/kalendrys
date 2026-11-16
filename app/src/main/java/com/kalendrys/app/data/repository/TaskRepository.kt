package com.kalendrys.app.data.repository

import android.util.Log
import com.kalendrys.app.data.local.dao.CompletionLogDao
import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderCatchUp
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.ShoppingCompletionOffer
import com.kalendrys.app.domain.ShoppingReset
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.reminder.ReminderScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fonte única das tarefas: mantém banco e alarmes de lembrete sempre em sincronia. */
class TaskRepository(
    private val taskDao: TaskDao,
    private val completionLogDao: CompletionLogDao,
    private val scheduler: ReminderScheduler,
    private val shoppingItemDao: ShoppingItemDao,
    /**
     * Roda o catch-up de lembretes (ADR-0003) ANTES de gravar em [addTask], [updateTask],
     * [restoreTask] e [undoCompletion]: fecha a janela no instante da mudança, para um horário já
     * passado não virar atrasado nem perdido. Passado pelo KalendrysApp (mesmo padrão do afterRestore do
     * BackupManager), sem dependência circular com o runner. Vazio nos testes de repositório.
     */
    private val beforeReminderChange: suspend () -> Unit = {},
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
        /** Lista de compras anexada já na criação (ADR-0001, P2), ou null. */
        shoppingListId: Long? = null,
    ): Task {
        closeReminderWindow()
        val task = timing.applyTo(
            Task(title = title.trim(), notes = notes.trim(), location = location.trim(), shoppingListId = shoppingListId),
            ZoneId.systemDefault(),
        )
        val saved = task.copy(id = taskDao.insert(task))
        scheduler.schedule(saved)
        return saved
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
        closeReminderWindow()
        taskDao.update(task)
        scheduler.cancel(task.id)
        if (!isFinished(task)) scheduler.schedule(task)
    }

    /** Exclui a tarefa: cancela o alarme e tira da tela o lembrete já exibido dela. */
    suspend fun deleteTask(task: Task) {
        scheduler.cancel(task.id)
        scheduler.dismissNotification(task.id)
        taskDao.delete(task)
    }

    /**
     * Reinsere uma tarefa excluída com o mesmo id, reconectando-a ao histórico de conclusões. O
     * vínculo com a lista de compras ([Task.shoppingListId]) volta junto (ADR-0001, P5): excluir a
     * tarefa nunca apagou a lista.
     */
    suspend fun restoreTask(task: Task) {
        closeReminderWindow()
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
     *
     * Por qualquer caminho (tela, lista de tarefas, oferta da lista, widget, ação da notificação), a
     * notificação de lembrete já exibida da tarefa some. O alarme da próxima ocorrência de uma
     * tarefa repetida não é tocado.
     */
    suspend fun logCompletion(taskId: Long, completedAt: Long = System.currentTimeMillis()): Long {
        scheduler.dismissNotification(taskId)
        val task = taskDao.byId(taskId)
        var early: EarlyCompletion? = null
        if (task != null && task.repeat == ReminderRepeat.NONE) {
            scheduler.cancel(taskId)
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate().toEpochDay()
            if (task.dueDate != null && task.dueDate > day) {
                taskDao.update(task.copy(dueDate = day, reminderAt = null))
                early = EarlyCompletion(previousDueDate = task.dueDate, previousReminderAt = task.reminderAt, rewrittenDueDate = day)
            }
        }
        val logId = completionLogDao.insert(CompletionLog(taskId = taskId, completedAt = completedAt))
        // Só entra no mapa quando a conclusão DE FATO reescreveu data e lembrete (tarefa única adiantada).
        if (early != null) earlyLock.withLock { earlyCompletions[logId] = early }
        val listId = task?.shoppingListId
        if (listId != null && ShoppingReset.taskResets(task.repeat)) {
            shoppingItemDao.resetPicked(listId, ShoppingReset.taskKey(logId))
        }
        return logId
    }

    /**
     * Desfaz uma conclusão; a tarefa volta a ficar pendente e o lembrete, se ainda vale, volta. As
     * marcações que essa conclusão tinha zerado voltam (se ainda for o último reinício da lista).
     * Só reagenda o alarme: a notificação que já tinha sido exibida NÃO é recriada.
     *
     * Tarefa única concluída ADIANTADA (o [logCompletion] trouxe a data para hoje e tirou o lembrete):
     * a data e o lembrete originais voltam, e SÓ DEPOIS o alarme é reagendado, desde que a tarefa
     * ainda esteja como a conclusão a deixou (mesma data, sem lembrete). Se o usuário editou a data
     * no meio, a edição dele vence e nada é restaurado. É MELHOR ESFORÇO: a informação fica em
     * memória ([earlyCompletions]); com o processo morto não há o que restaurar, e o desfazer segue
     * como antes. A solução definitiva (colunas no CompletionLog) vem na v1.8.0.
     */
    suspend fun undoCompletion(logId: Long) {
        // Fecha a janela do catch-up antes de reabrir a tarefa (ADR-0003): o lembrete que venceu
        // enquanto ela estava concluída não vira atrasado nem perdido.
        closeReminderWindow()
        val log = completionLogDao.byId(logId) ?: return
        completionLogDao.deleteById(logId)
        shoppingItemDao.restoreReset(ShoppingReset.taskKey(logId))
        val early = earlyLock.withLock { earlyCompletions.remove(logId) }
        var task = taskDao.byId(log.taskId) ?: return
        if (early != null && task.dueDate == early.rewrittenDueDate && task.reminderAt == null) {
            task = task.copy(dueDate = early.previousDueDate, reminderAt = early.previousReminderAt)
            taskDao.update(task)
        }
        if (!isFinished(task)) scheduler.schedule(task)
    }

    /** Data e lembrete que uma conclusão adiantada sobrescreveu (ver [undoCompletion]). */
    private data class EarlyCompletion(
        val previousDueDate: Long,
        val previousReminderAt: Long?,
        /** Data que o [logCompletion] gravou (o dia da conclusão). */
        val rewrittenDueDate: Long,
    )

    /**
     * logId → conclusão adiantada, só as [MAX_EARLY_COMPLETIONS] mais recentes (o "Desfazer" é uma
     * snackbar curta). Acessado de tela, widget e receivers: sempre sob [earlyLock].
     */
    private val earlyCompletions = object : LinkedHashMap<Long, EarlyCompletion>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, EarlyCompletion>?): Boolean =
            size > MAX_EARLY_COMPLETIONS
    }
    private val earlyLock = Mutex()

    /** Para testes: quantas conclusões adiantadas ainda podem ser desfeitas com restauração. */
    internal suspend fun earlyCompletionCount(): Int = earlyLock.withLock { earlyCompletions.size }

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
            ShoppingCompletionOffer.LinkedTask(task.id, pending = ShoppingCompletionOffer.isPending(status, task.repeat))
        }
    }

    /**
     * Plano do catch-up de lembretes em `(lastSeen, now]` ([ReminderCatchUp.plan]), com as tarefas
     * de cada ocorrência atrasada. Só lê o banco; quem mostra, reagenda e grava a marca é o runner.
     */
    suspend fun reminderCatchUpPlan(lastSeen: Long, now: Long, zone: ZoneId): Pair<ReminderCatchUp.Plan, Map<Long, Task>> {
        if (now <= lastSeen) return ReminderCatchUp.Plan.EMPTY to emptyMap()
        val tasks = taskDao.all().filter { it.reminderAt != null }
        val last = completionLogDao.lastCompletions().associate { it.taskId to it.completedAt }
        val firstDay = Instant.ofEpochMilli(lastSeen).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val completionDays = completionLogDao.between(
            start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli(),
            end = lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        ).groupBy({ it.taskId }) { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, days) -> days.toSet() }
        val plan = ReminderCatchUp.plan(tasks, last, completionDays, lastSeen, now, zone)
        val byId = tasks.associateBy { it.id }
        return plan to plan.late.mapNotNull { byId[it.taskId] }.associateBy { it.id }
    }

    /** Cancela o alarme pendente da tarefa, sem mexer na notificação já exibida (higiene do catch-up). */
    fun cancelReminderAlarm(taskId: Long) {
        scheduler.cancel(taskId)
    }

    /**
     * Agenda a próxima ocorrência de cada tarefa estritamente depois de [now]. Chamado só pelo
     * catch-up de lembretes (ADR-0003, passo 4, depois da entrega) e pela linha de base do restauro.
     */
    suspend fun rescheduleReminders(now: Long = System.currentTimeMillis()) {
        scheduler.rescheduleAll(tasksToRemind(now))
    }

    /**
     * As tarefas cujo lembrete o app mantém agendado em [now]: com lembrete pendente e não
     * encerradas. Um conjunto só para o [rescheduleReminders] e para o diagnóstico de alarmes.
     * Só leitura.
     */
    suspend fun tasksToRemind(now: Long = System.currentTimeMillis()): List<Task> {
        val zone = ZoneId.systemDefault()
        val last = completionLogDao.lastCompletions().associate { it.taskId to it.completedAt }
        return taskDao.withPendingReminder(now).filterNot { TaskSchedule.isFinished(it, last[it.id], zone) }
    }

    /**
     * Fecha a janela do catch-up antes de uma alteração ([beforeReminderChange]). Uma falha nele é
     * registrada e a alteração segue: perder a gravação da tarefa por causa de uma notificação seria
     * pior. No catch-up dos pontos de entrada a falha continua propagando.
     */
    private suspend fun closeReminderWindow() {
        try {
            beforeReminderChange()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Catch-up de lembretes falhou antes da alteração; a alteração segue", error)
        }
    }

    /** Sem repetição e já concluída (no dia dela ou depois): não lembra mais. */
    private suspend fun isFinished(task: Task): Boolean =
        TaskSchedule.isFinished(task, completionLogDao.lastCompletion(task.id), ZoneId.systemDefault())

    private companion object {
        const val TAG = "TaskRepository"
        const val MAX_EARLY_COMPLETIONS = 20
    }
}
