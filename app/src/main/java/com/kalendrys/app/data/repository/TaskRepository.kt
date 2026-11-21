package com.kalendrys.app.data.repository

import android.util.Log
import com.kalendrys.app.data.local.dao.CompletionLogDao
import com.kalendrys.app.data.local.dao.DateRestore
import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderCatchUp
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.ShoppingCompletionOffer
import com.kalendrys.app.domain.ShoppingReset
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.reminder.ReminderAlarm
import com.kalendrys.app.reminder.ReminderScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fonte única das tarefas: mantém banco e alarmes de lembrete sempre em sincronia. */
class TaskRepository(
    private val taskDao: TaskDao,
    private val completionLogDao: CompletionLogDao,
    private val scheduler: ReminderScheduler,
    private val shoppingItemDao: ShoppingItemDao,
    /**
     * Roda o catch-up de lembretes (ADR-0003) ANTES de gravar em [addTask], [edit] (data, hora e repetição),
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

    /**
     * Cria a tarefa e agenda o lembrete. Com [newListName] não em branco, cria TAMBÉM uma lista nova
     * ([ShoppingList.named], a mesma regra do [ShoppingRepository.createList]) e vincula a tarefa a ela,
     * as duas gravações numa transação só ([TaskDao.insertTaskWithNewList], v1.8.0 E6): falhar a tarefa
     * não deixa lista avulsa. Nesse caso [shoppingListId] é ignorado. Nome em branco não cria lista.
     * Catch-up e agendamento ficam fora da transação, como sempre.
     */
    suspend fun addTask(
        title: String,
        notes: String = "",
        timing: TaskTiming = TaskTiming(date = null),
        location: String = "",
        /** Lista de compras existente anexada já na criação (ADR-0001, P2), ou null. */
        shoppingListId: Long? = null,
        /** Nome da lista NOVA a criar junto com a tarefa ("Criar nova" no painel), ou null. */
        newListName: String? = null,
    ): Task {
        closeReminderWindow()
        val newList = newListName?.takeIf { it.isNotBlank() }?.let(ShoppingList::named)
        val task = timing.applyTo(
            Task(
                title = title.trim(),
                notes = notes.trim(),
                location = location.trim(),
                shoppingListId = if (newList != null) null else shoppingListId,
            ),
            ZoneId.systemDefault(),
        )
        return alarmLock.withLock {
            val saved = if (newList == null) {
                task.copy(id = taskDao.insert(task))
            } else {
                // A transação troca o vínculo pelo da lista gravada: relê para devolver a tarefa como ficou.
                checkNotNull(taskDao.byId(taskDao.insertTaskWithNewList(newList, task)))
            }
            scheduleNext(saved)
            saved
        }
    }

    /**
     * Tarefa da notificação de lembrete (reexibir depois de "Adiar", "Concluir"): a versão atual do
     * banco, ou `null` se ela foi excluída, encerrada ou (recorrente) já concluída NO DIA DA OCORRÊNCIA
     * que a notificação mostra ([occurrenceDay]; null em notificação antiga, sem o dia = hoje). Assim,
     * concluir ontem pela Hoje e depois pela notificação de ontem não grava duas vezes (NB-E5-A). Só
     * lê: não mexe em alarmes nem no banco.
     */
    suspend fun taskForReminder(taskId: Long, now: Long = System.currentTimeMillis(), occurrenceDay: LocalDate? = null): Task? {
        val task = taskDao.byId(taskId) ?: return null
        if (task.repeat == ReminderRepeat.NONE) return task.takeUnless { isFinished(task) }
        val zone = ZoneId.systemDefault()
        val day = occurrenceDay ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val doneThatDay = completionLogDao.isCompletedBetween(
            taskId = taskId,
            start = day.atStartOfDay(zone).toInstant().toEpochMilli(),
            end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        return task.takeUnless { doneThatDay }
    }

    /**
     * "Concluir" da notificação de lembrete (NB-E5-A da v1.8.0). A notificação sabe de qual ocorrência
     * é ([occurrenceDay], o dia em que ela tocou). Tarefa REPETIDA com dia anterior a hoje grava a
     * conclusão NAQUELE dia, pelo mesmo caminho da conclusão retroativa da Hoje
     * ([TaskSchedule.completionInstantOn] + [logCompletion]): hoje continua pendente e o alarme de hoje
     * intacto. Dia de hoje, tarefa única ou notificação sem o dia: agora, como sempre.
     *
     * Guardada pelo dia da ocorrência ([taskForReminder]): já concluída nesse dia, única encerrada ou
     * excluída não grava de novo. Devolve se gravou.
     */
    suspend fun completeFromReminder(taskId: Long, occurrenceDay: LocalDate?, now: Long = System.currentTimeMillis()): Boolean {
        val task = taskForReminder(taskId, now, occurrenceDay) ?: return false
        val completedAt = if (task.repeat != ReminderRepeat.NONE && occurrenceDay != null) {
            TaskSchedule.completionInstantOn(occurrenceDay, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        } else {
            now
        }
        logCompletion(taskId, completedAt, now)
        return true
    }

    /**
     * Salvar na hora (v1.9.0, ADR-0002 15.2): aplica [change] sobre a linha MAIS RECENTE da tarefa,
     * lida DENTRO da [alarmLock], e grava só as colunas da mudança. Concluir, desfazer e excluir a
     * lista vinculada por fora, com o detalhe aberto, não são desfeitos por uma edição.
     *
     * - Mudança inválida ([TaskChange.applyTo] devolve null; título vazio, repetição sem dia) não grava.
     * - Tarefa que não existe mais, ou mudança que não muda nada: nada acontece.
     * - Vincular a uma lista excluída no meio não grava (a lista é conferida na transação).
     * - Lista nova ([TaskChange.NewShoppingList]): cria e vincula numa transação só.
     * - Alarme: SÓ [TaskChange.Timing] fecha a janela do catch-up (antes e FORA da trava, ordem da 14.3)
     *   e refaz o alarme pela conta de sempre ([scheduleNext]). Texto e lista não tocam em alarme.
     *
     * A notificação de lembrete já na tela NÃO é reemitida com o título novo: ela continua com o texto
     * de quando tocou (como antes da v1.9.0); a próxima sai com o título novo.
     *
     * Devolve se gravou.
     */
    suspend fun edit(taskId: Long, change: TaskChange, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (change.touchesReminder) closeReminderWindow()
        return alarmLock.withLock {
            val current = taskDao.byId(taskId) ?: return@withLock false
            val updated = change.applyTo(current, zone) ?: return@withLock false
            when (change) {
                is TaskChange.NewShoppingList -> {
                    taskDao.linkNewList(taskId, ShoppingList.named(change.name))
                    return@withLock true
                }
                is TaskChange.ShoppingList -> {
                    if (updated.shoppingListId == current.shoppingListId) return@withLock false
                    val listId = updated.shoppingListId
                    if (listId == null) taskDao.updateShoppingList(taskId, null) else return@withLock taskDao.linkExistingList(taskId, listId)
                }
                else -> {
                    if (updated == current) return@withLock false
                    when (change) {
                        is TaskChange.Title -> taskDao.updateTitle(taskId, updated.title)
                        is TaskChange.Notes -> taskDao.updateNotes(taskId, updated.notes)
                        is TaskChange.Location -> taskDao.updateLocation(taskId, updated.location)
                        // Gravar e reagendar sem cancelamento (NB-E1-B, como o change{} da rotina): cortado
                        // entre os dois, o banco ficaria com a data nova e o alarme antigo (ou nenhum).
                        is TaskChange.Timing -> withContext(NonCancellable) {
                            taskDao.updateTiming(
                                id = taskId,
                                dueDate = updated.dueDate,
                                reminderAt = updated.reminderAt,
                                repeat = updated.repeat,
                                intervalEvery = updated.intervalEvery,
                                intervalUnit = updated.intervalUnit,
                                byWeekdays = updated.byWeekdays,
                            )
                            // A conta de sempre: cancela e, se ainda lembra, marca a próxima entregue.
                            scheduler.cancel(taskId)
                            if (!isFinished(updated)) scheduleNext(updated)
                        }
                        is TaskChange.NewShoppingList, is TaskChange.ShoppingList -> Unit // tratados acima
                    }
                }
            }
            true
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
    suspend fun logCompletion(
        taskId: Long,
        completedAt: Long = System.currentTimeMillis(),
        now: Long = System.currentTimeMillis(),
    ): Long = alarmLock.withLock {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
        // Conclusão RETROATIVA (num dia anterior a hoje, pela Hoje): não tem efeito de hoje, a mesma regra
        // das rotinas (ShoppingReset.routineResets): não tira a notificação da tela, não zera a lista e
        // não mexe no alarme da próxima ocorrência. Nem conclusão adiantada (v1.8.0, decisão do Esquadro).
        val retroactive = day.isBefore(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        val task = taskDao.byId(taskId)
        // A notificação na tela é sempre a da ÚLTIMA ocorrência que já tocou (um id por tarefa). Sai na
        // conclusão de hoje e na da tarefa única (que encerra; A1 do Esquadro). Na retroativa de REPETIDA,
        // sai só se o dia concluído é o dia dessa última ocorrência: antes do horário de hoje a visível é a
        // de ontem, e deixá-la faria o "Concluir" dela gravar hoje e perder o lembrete de hoje (NB-E4-A).
        val dismiss = !retroactive || task == null || task.repeat == ReminderRepeat.NONE ||
            ReminderCatchUp.lastOccurrenceSince(task, day, now, zone)?.let { it.day(zone) == day } == true
        if (dismiss) scheduler.dismissNotification(taskId)
        if (task != null && task.repeat == ReminderRepeat.NONE && isFinished(task)) {
            completionLogDao.latestForTask(taskId)?.let { return@withLock it.id }
        }
        val log = CompletionLog(taskId = taskId, completedAt = completedAt)
        var logId: Long? = null
        if (task != null && task.repeat == ReminderRepeat.NONE) {
            scheduler.cancel(taskId)
            val dayEpoch = day.toEpochDay()
            if (!retroactive && task.dueDate != null && task.dueDate > dayEpoch) {
                // Data e lembrete sobrescritos vão no próprio registro, na mesma transação da tarefa. Só
                // data e lembrete são gravados (15.2): o resto da linha fica como está no banco.
                logId = completionLogDao.rewriteAndInsert(
                    taskId = taskId,
                    rewrittenDueDate = dayEpoch,
                    log = log.copy(previousDueDate = task.dueDate, previousReminderAt = task.reminderAt, rewrittenDueDate = dayEpoch),
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
            // Apagar o registro e devolver a data numa transação só (A1 da revisão da E0). Só data e
            // lembrete voltam (15.2): título, texto e lista ficam como estão no banco.
            completionLogDao.deleteAndRestore(
                logId,
                restored?.let { DateRestore(it.id, it.dueDate, it.reminderAt) },
            )
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
