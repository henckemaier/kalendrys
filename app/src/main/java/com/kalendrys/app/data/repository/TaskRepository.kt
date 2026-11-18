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
import com.kalendrys.app.reminder.ReminderAlarm
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
        return alarmLock.withLock {
            val saved = task.copy(id = taskDao.insert(task))
            scheduleNext(saved)
            saved
        }
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
        alarmLock.withLock {
            taskDao.update(task)
            scheduler.cancel(task.id)
            if (!isFinished(task)) scheduleNext(task)
        }
    }

    /** Exclui a tarefa: cancela o alarme e tira da tela o lembrete já exibido dela. */
    suspend fun deleteTask(task: Task) = alarmLock.withLock {
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
        alarmLock.withLock {
            taskDao.insert(task)
            if (!isFinished(task)) scheduleNext(task)
        }
    }

    /**
     * Devolve o id do evento, usado para desfazer a conclusão. Uma tarefa sem repetição fica
     * encerrada: o lembrete dela, se ainda não tocou, é cancelado. Concluída antes do dia dela,
     * ela passa a ser de hoje (foi feita hoje) e perde o lembrete futuro; a data e o lembrete que
     * isso sobrescreveu ficam no PRÓPRIO registro (banco 11), para o [undoCompletion].
     *
     * Tarefa única JÁ ENCERRADA (toque duplo, widget e notificação ao mesmo tempo): devolve o
     * registro existente e não cria outro (NB-3 da v1.8.0). As conclusões passam por [alarmLock]
     * para duas chamadas simultâneas não lerem a tarefa ainda aberta e gravarem dois registros.
     *
     * Tarefa que se REPETE com lista anexada zera as marcações da lista (ADR-0001, P4), de forma
     * reversível pelo [undoCompletion]. Ordem: grava o log e depois reinicia com o id dele (uma falha
     * entre os dois deixa a conclusão sem reinício, o que é inofensivo). Tarefa única mantém as
     * marcações. Mora aqui, e não na tela, para valer também no widget e na notificação.
     *
     * Por qualquer caminho (tela, lista de tarefas, oferta da lista, widget, ação da notificação), a
     * notificação de lembrete já exibida da tarefa some. Tarefa repetida tem o alarme refeito para a
     * próxima ocorrência que será entregue (v1.8.0, E1: concluída hoje, o alarme de hoje sai).
     */
    suspend fun logCompletion(taskId: Long, completedAt: Long = System.currentTimeMillis()): Long = alarmLock.withLock {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
        // Conclusão RETROATIVA (num dia anterior a hoje, pela Hoje): não tem efeito de hoje, a mesma regra
        // das rotinas (ShoppingReset.routineResets): não tira a notificação da tela, não zera a lista e
        // não mexe no alarme da próxima ocorrência. Nem conclusão adiantada (v1.8.0, decisão do Esquadro).
        val retroactive = day.isBefore(LocalDate.now(zone))
        val task = taskDao.byId(taskId)
        // A notificação do lembrete sai, menos na retroativa de tarefa REPETIDA (a ocorrência de hoje é
        // outra). A única que encerra não tem ocorrência de hoje: a dela sai (A1 da revisão do Esquadro).
        if (!retroactive || task?.repeat == ReminderRepeat.NONE) scheduler.dismissNotification(taskId)
        if (task != null && task.repeat == ReminderRepeat.NONE && isFinished(task)) {
            completionLogDao.latestForTask(taskId)?.let { return@withLock it.id }
        }
        val log = CompletionLog(taskId = taskId, completedAt = completedAt)
        var logId: Long? = null
        if (task != null && task.repeat == ReminderRepeat.NONE) {
            scheduler.cancel(taskId)
            val dayEpoch = day.toEpochDay()
            if (!retroactive && task.dueDate != null && task.dueDate > dayEpoch) {
                // Data e lembrete sobrescritos vão no próprio registro, na mesma transação da tarefa.
                logId = completionLogDao.rewriteAndInsert(
                    task.copy(dueDate = dayEpoch, reminderAt = null),
                    log.copy(previousDueDate = task.dueDate, previousReminderAt = task.reminderAt, rewrittenDueDate = dayEpoch),
                )
            }
        }
        val id = logId ?: completionLogDao.insert(log)
        // Repetida: o alarme passa para a próxima ocorrência que será ENTREGUE (a de hoje, concluída,
        // a entrega descartaria). Única: já foi cancelada acima e fica encerrada.
        if (task != null && task.repeat != ReminderRepeat.NONE && !retroactive) scheduleNext(task)
        val listId = task?.shoppingListId
        if (listId != null && !retroactive && ShoppingReset.taskResets(task.repeat)) {
            shoppingItemDao.resetPicked(listId, ShoppingReset.taskKey(id))
        }
        id
    }

    /**
     * Desfaz uma conclusão; a tarefa volta a ficar pendente e o lembrete, se ainda vale, volta. As
     * marcações que essa conclusão tinha zerado voltam (se ainda for o último reinício da lista).
     * Só reagenda o alarme: a notificação que já tinha sido exibida NÃO é recriada.
     *
     * Tarefa única concluída ADIANTADA (o [logCompletion] trouxe a data para hoje e tirou o lembrete):
     * a data e o lembrete originais voltam a partir do próprio registro, a qualquer momento (mesmo
     * depois de o processo morrer), e SÓ DEPOIS o alarme é reagendado, desde que a tarefa ainda
     * esteja como a conclusão a deixou (mesma data, sem lembrete). Se o usuário editou a data ou pôs
     * lembrete no meio, a edição dele vence e nada é restaurado.
     */
    suspend fun undoCompletion(logId: Long) {
        // Fecha a janela do catch-up antes de reabrir a tarefa (ADR-0003): o lembrete que venceu
        // enquanto ela estava concluída não vira atrasado nem perdido.
        closeReminderWindow()
        alarmLock.withLock {
            val log = completionLogDao.byId(logId) ?: return@withLock
            val current = taskDao.byId(log.taskId)
            val previousDue = log.previousDueDate
            // Devolve data e lembrete só se a tarefa ainda está como a conclusão a deixou.
            val restored = current?.takeIf {
                previousDue != null && it.dueDate == log.rewrittenDueDate && it.reminderAt == null
            }?.copy(dueDate = previousDue, reminderAt = log.previousReminderAt)
            // Apagar o registro e devolver a data numa transação só (A1 da revisão da E0).
            completionLogDao.deleteAndRestore(logId, restored)
            shoppingItemDao.restoreReset(ShoppingReset.taskKey(logId))
            val task = restored ?: current ?: return@withLock
            if (!isFinished(task)) scheduleNext(task)
        }
    }

    /**
     * Trava das gravações que mexem em alarme de tarefa (concluir, desfazer, criar, editar, restaurar,
     * excluir) e do reagendamento geral do catch-up ([rescheduleReminders]): quem lê tarefas e conclusões
     * para marcar alarmes faz isso sem outra gravação no meio (NB-E1-B da v1.8.0).
     *
     * ORDEM DAS TRAVAS: a do [com.kalendrys.app.reminder.ReminderCatchUpRunner] por FORA, esta por DENTRO,
     * nunca o contrário. Por isso o fechamento da janela do catch-up ([closeReminderWindow], que entra
     * no runner) é chamado SEMPRE antes e fora desta trava. Não é reentrante: só os pontos de entrada
     * públicos a pegam; o [scheduleNext] privado (chamado de dentro deles) não.
     */
    private val alarmLock = Mutex()

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
    suspend fun rescheduleReminders(now: Long = System.currentTimeMillis()) = alarmLock.withLock {
        // Lê tarefas e conclusões e marca DENTRO da trava (NB-E1-B): um concluir ou desfazer no meio não
        // vê seu alarme sobrescrito por uma leitura anterior a ele.
        val zone = ZoneId.systemDefault()
        val tasks = tasksToRemind(now)
        val doneDays = completionDaysFrom(now, zone)
        scheduler.rescheduleAll(
            tasks.mapNotNull { task -> nextDelivered(task, now, zone, doneDays[task.id].orEmpty())?.let { ReminderAlarm(task, it) } },
        )
    }

    /**
     * Marca o alarme de [task] na próxima ocorrência que será ENTREGUE depois de [now] (ADR-0002,
     * 14.3): a mesma conta do [nextReminderToDeliver]. Sem ocorrência a entregar, CANCELA o alarme da
     * tarefa (NB-1 da revisão da E1): não fica um alarme marcado que a conta já não prevê.
     */
    private suspend fun scheduleNext(task: Task, now: Long = System.currentTimeMillis()) {
        val zone = ZoneId.systemDefault()
        val doneDays = completionDaysFrom(now, zone)[task.id].orEmpty()
        val at = nextDelivered(task, now, zone, doneDays)
        if (at != null) scheduler.schedule(task, at) else scheduler.cancel(task.id)
    }

    /** Dias com conclusão de cada tarefa, de hoje em diante (as ocorrências candidatas são depois de [now]). */
    private suspend fun completionDaysFrom(now: Long, zone: ZoneId): Map<Long, Set<LocalDate>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return completionLogDao.between(start = today, end = Long.MAX_VALUE)
            .groupBy({ it.taskId }) { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, days) -> days.toSet() }
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
     * Próximo lembrete de tarefa que de fato CHEGA depois de [now] (epoch millis), para o diagnóstico
     * de alarmes; `null` sem nenhum. Pula a ocorrência que o catch-up descartaria
     * ([ReminderCatchUp.isDiscarded]: repetida já concluída no dia), avançando no máximo
     * [MAX_DISCARDED_OCCURRENCES] vezes por tarefa. Desde a v1.8.0 (E1) é a MESMA conta que o
     * agendador usa ([scheduleNext], [rescheduleReminders]): o diagnóstico mostra o alarme marcado. Só leitura.
     */
    suspend fun nextReminderToDeliver(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val tasks = tasksToRemind(now)
        if (tasks.isEmpty()) return null
        val doneDays = completionDaysFrom(now, zone)
        return tasks.mapNotNull { nextDelivered(it, now, zone, doneDays[it.id].orEmpty()) }.minOrNull()
    }

    private fun nextDelivered(task: Task, now: Long, zone: ZoneId, doneDays: Set<LocalDate>): Long? {
        var at = TaskSchedule.nextReminderMillis(task, now, zone) ?: return null
        repeat(MAX_DISCARDED_OCCURRENCES) {
            if (!ReminderCatchUp.isDiscarded(task, at, doneDays, zone)) return at
            at = TaskSchedule.nextReminderMillis(task, at, zone) ?: return null
        }
        return null
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
        /**
         * Limite de ocorrências puladas por tarefa no [nextReminderToDeliver]. Cada uma é um dia com
         * conclusão a partir de hoje; na prática é uma (a de hoje).
         */
        const val MAX_DISCARDED_OCCURRENCES = 32
    }
}
