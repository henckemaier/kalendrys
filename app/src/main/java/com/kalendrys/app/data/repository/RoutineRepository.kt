package com.kalendrys.app.data.repository

import android.util.Log
import com.kalendrys.app.data.local.dao.RoutineCompletionDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.dao.RoutineOverrideDao
import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.domain.RoutineCatchUp
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.domain.ShoppingReset
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.reminder.RoutineNotifications
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Marca do catch-up dos avisos de bloco (ADR-0003, 4.3.1): até aqui os inícios de bloco foram
 * avisados ou descartados. No app é a `routine_last_seen_at` do
 * [com.kalendrys.app.data.settings.SettingsRepository]; nos testes, uma marca em memória.
 */
interface RoutineCatchUpMark {
    suspend fun get(): Long?

    suspend fun set(millis: Long)
}

/**
 * Fonte única das rotinas: banco, alarme do próximo início e notificações sempre em sincronia.
 *
 * O aviso de início de bloco é entregue pelo [catchUp] (ADR-0003, 4.3.1); o alarme só acorda o app.
 * Toda alteração de rotina roda o catch-up ANTES de gravar (fecha a janela com a configuração
 * antiga) e só agenda depois, tudo sob o mesmo [mutex]. Assim nenhum `alarms.scheduleNext` substitui
 * um aviso pendente ainda não entregue, e criar ou mover um bloco para um horário já passado não o
 * avisa como atrasado.
 *
 * [deliverDueBlocks] mostra os avisos (no app, [RoutineNotifications.show] com exceções e progresso
 * da lista): o repositório não monta notificação. [mark] guarda a marca do catch-up.
 */
class RoutineRepository(
    private val routineDao: RoutineDao,
    private val completionDao: RoutineCompletionDao,
    private val overrideDao: RoutineOverrideDao,
    private val alarms: RoutineAlarms,
    private val notifications: RoutineNotifications,
    private val shoppingItemDao: ShoppingItemDao,
    /** Obrigatória: uma marca esquecida em memória faria o app reavisar a cada início de processo. */
    private val mark: RoutineCatchUpMark,
    private val deliverDueBlocks: suspend (List<Routine>, LocalDate) -> Unit = { _, _ -> },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Uma execução por vez: catch-up, alteração + agendamento e todo `alarms.scheduleNext`. Não é reentrante. */
    private val mutex = Mutex()

    /** Todas as rotinas, inclusive as excluídas; quem decide o que aparece em cada dia é o [RoutineSchedule]. */
    val routines: Flow<List<Routine>> = routineDao.observeAll()

    /**
     * Rotinas que entram no retrato do dia (resumo/widgets): as pausadas ficam de fora. A lista de
     * gestão ([routines]) continua mostrando as pausadas, para dar pra retomá-las.
     */
    val scheduledRoutines: Flow<List<Routine>> = routineDao.observeScheduled()

    /**
     * Exceções pontuais agrupadas por rotina, de forma reativa: o display (trilha, dots, calendário)
     * honra SKIP/RESCHEDULE assim que o usuário cria ou remove uma exceção.
     */
    val overrides: Flow<Map<Long, List<RoutineOverride>>> =
        overrideDao.observeAll().map { all -> all.groupBy { it.routineId } }

    fun observeRoutine(id: Long): Flow<Routine?> = routineDao.observeById(id)

    /** Conclusões das ocorrências entre [start] (inclusive) e [endExclusive]. */
    fun observeCompletionsBetween(start: LocalDate, endExclusive: LocalDate): Flow<List<RoutineCompletion>> =
        completionDao.observeBetween(start.toEpochDay(), endExclusive.toEpochDay())

    /**
     * Cria a rotina e agenda. Com [newListName] não em branco, cria TAMBÉM uma lista nova
     * ([ShoppingList.named]) e vincula a rotina a ela numa transação só (v1.9.0, 15.5, como o painel
     * de nova tarefa já faz): falhar a rotina não deixa lista avulsa. Nesse caso o
     * [Routine.shoppingListId] de [routine] é ignorado.
     */
    suspend fun add(routine: Routine, newListName: String? = null): Routine = change {
        val newList = newListName?.takeIf { it.isNotBlank() }?.let(ShoppingList::named)
        if (newList == null) {
            routine.copy(id = routineDao.insert(routine))
        } else {
            checkNotNull(routineDao.byId(routineDao.insertRoutineWithNewList(newList, routine)))
        }
    }

    /**
     * Salvar na hora (v1.9.0, ADR-0002 15.2): aplica [change] sobre a linha MAIS RECENTE da rotina,
     * lida DENTRO do [mutex], e grava só as colunas da mudança. Pausar, arquivar ou excluir a lista
     * vinculada por fora, com o editor aberto, não são desfeitos por uma edição.
     *
     * - Mudança inválida ([RoutineChange.applyTo] devolve null) não grava. Rotina inexistente ou
     *   mudança que não muda nada: nada acontece.
     * - Vincular a uma lista excluída no meio não grava; lista nova cria e vincula numa transação só.
     * - Alarme: SÓ as mudanças de [RoutineChange.touchesSchedule] (início, dias, intervalo, vigência,
     *   lembrar) passam pelo [change]: catch-up com a configuração antiga, gravação e reagendamento.
     *   Texto, duração, cor, ícone, categoria e lista gravam sob o mesmo [mutex] sem tocar em alarme.
     *
     * O aviso de bloco já na tela NÃO é reemitido com o título novo; o próximo sai com ele.
     *
     * Devolve se gravou.
     */
    suspend fun edit(routineId: Long, routineChange: RoutineChange): Boolean =
        if (routineChange.touchesSchedule) {
            change { editLocked(routineId, routineChange) }
        } else {
            mutex.withLock { withContext(NonCancellable) { editLocked(routineId, routineChange) } }
        }

    /** Só dentro do [mutex] (pelo [edit]). */
    private suspend fun editLocked(routineId: Long, change: RoutineChange): Boolean {
        val current = routineDao.byId(routineId) ?: return false
        val updated = change.applyTo(current) ?: return false
        when (change) {
            is RoutineChange.NewShoppingList -> {
                routineDao.linkNewList(routineId, ShoppingList.named(change.name))
                return true
            }
            is RoutineChange.ShoppingList -> {
                if (updated.shoppingListId == current.shoppingListId) return false
                val listId = updated.shoppingListId ?: run {
                    routineDao.updateShoppingList(routineId, null)
                    return true
                }
                return routineDao.linkExistingList(routineId, listId)
            }
            else -> Unit
        }
        if (updated == current) return false
        when (change) {
            is RoutineChange.Title -> routineDao.updateTitle(routineId, updated.title)
            is RoutineChange.Notes -> routineDao.updateNotes(routineId, updated.notes)
            is RoutineChange.Start -> routineDao.updateStart(routineId, updated.startMinute)
            is RoutineChange.Duration -> routineDao.updateDuration(routineId, updated.durationMinutes)
            is RoutineChange.Days -> routineDao.updateDays(routineId, updated.daysOfWeek)
            is RoutineChange.WeekInterval -> routineDao.updateWeekInterval(routineId, updated.weekInterval)
            is RoutineChange.ValidFrom, is RoutineChange.ValidUntil ->
                routineDao.updateValidity(routineId, updated.startEpochDay, updated.endEpochDay)
            is RoutineChange.Remind -> routineDao.updateRemind(routineId, updated.remind)
            is RoutineChange.Category -> routineDao.updateCategory(routineId, updated.categoryId)
            is RoutineChange.Color -> routineDao.updateColor(routineId, updated.color)
            is RoutineChange.Icon -> routineDao.updateIcon(routineId, updated.icon)
            is RoutineChange.NewShoppingList, is RoutineChange.ShoppingList -> Unit // tratados acima
        }
        return true
    }

    /**
     * "Excluir": some de hoje em diante, mas os dias passados mantêm o bloco e a conclusão. Grava só
     * a data de arquivamento (v1.9.0): uma edição feita depois de [routine] ter sido lida não se perde.
     */
    suspend fun archive(routine: Routine, now: Long = System.currentTimeMillis()): Unit = change {
        routineDao.updateArchivedAt(routine.id, now)
        notifications.cancel(routine.id)
    }

    /**
     * Desfaz o [archive] (Desfazer de "Todas as rotinas", v1.8.0, E2): a rotina volta como está no
     * banco, só sem a data de arquivamento, e os alarmes são refeitos pelo mesmo [change]. O
     * catch-up antes de gravar impede que devolver às 9:20 o bloco das 9:00 o avise. O aviso que o
     * arquivamento tirou da tela não volta. Rotina inexistente ou já ativa: nada muda.
     */
    suspend fun unarchive(routineId: Long): Unit = change {
        val current = routineDao.byId(routineId) ?: return@change
        if (current.archivedAt != null) routineDao.updateArchivedAt(routineId, null)
    }

    /**
     * "Pausar": a rotina para de agendar e avisar, mas a configuração e o histórico ficam
     * intactos. Distinta de arquivar (excluir): dá pra retomar depois. Reagenda sem ela e tira
     * da tela qualquer aviso já mostrado. Grava só a pausa (v1.9.0): pausar com o editor aberto e
     * editar depois, ou o contrário, não desfaz o outro lado.
     */
    suspend fun pause(routine: Routine, now: Long = System.currentTimeMillis()): Unit = change {
        routineDao.updatePausedAt(routine.id, now)
        notifications.cancel(routine.id)
    }

    /** "Retomar": a rotina volta a agendar/avisar com a configuração e o histórico preservados. */
    suspend fun resume(routine: Routine): Unit = change {
        routineDao.updatePausedAt(routine.id, null)
    }

    /**
     * Idempotente: tocar duas vezes em "Concluir" (widget, notificação) não desfaz. Confere se a
     * rotina ainda existe, porque a notificação pode ser de uma rotina já excluída.
     *
     * Concluir a ocorrência de HOJE de uma rotina (não arquivada) com lista anexada zera as marcações
     * da lista (ADR-0001, P4), de forma reversível pelo [uncomplete]. Mora aqui, e não na tela, para
     * valer também no widget e na ação "Concluir" da notificação. Só reinicia quando a conclusão é
     * nova: um segundo toque não sobrescreve as marcações guardadas pelo primeiro.
     *
     * Devolve se a conclusão foi GRAVADA agora (v1.9.0, E4): falso se já estava feita ou se a rotina não
     * existe. Quem oferece Desfazer só oferece quando gravou: um segundo toque (widget e Hoje juntos) não
     * pode gerar um Desfazer que apagaria a conclusão do primeiro. Widget e notificação ignoram o retorno.
     */
    suspend fun complete(routineId: Long, date: LocalDate, today: LocalDate = LocalDate.now()): Boolean = change {
        val routine = routineDao.byId(routineId) ?: return@change false
        val inserted = completionDao.insert(RoutineCompletion(routineId = routineId, epochDay = date.toEpochDay())) != -1L
        if (date == today) notifications.cancel(routineId)
        val listId = routine.shoppingListId
        if (inserted && listId != null && routine.archivedAt == null && ShoppingReset.routineResets(date, today)) {
            shoppingItemDao.resetPicked(listId, ShoppingReset.routineKey(routineId, date.toEpochDay()))
        }
        inserted
    }

    /**
     * Desfaz a conclusão e devolve as marcações que ela tinha zerado (se ainda for o último reinício).
     * O catch-up antes de gravar impede que desfazer às 9:20 a conclusão do bloco das 9:00 o avise.
     */
    suspend fun uncomplete(routineId: Long, date: LocalDate): Unit = change {
        completionDao.delete(routineId, date.toEpochDay())
        shoppingItemDao.restoreReset(ShoppingReset.routineKey(routineId, date.toEpochDay()))
    }

    /**
     * Bloco a reexibir num adiamento ("Adiar") do aviso: a rotina se ela ainda existe, está ativa,
     * ocorre em [date] e não foi concluída nesse dia — senão `null`. Só leitura; confere
     * existência/conclusão como paridade do T13 (não decide exceções — isso é do [RoutineSchedule]).
     */
    suspend fun routineForNotification(routineId: Long, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Routine? {
        val routine = routineDao.byId(routineId) ?: return null
        if (routine.archivedAt != null) return null
        if (!RoutineSchedule.occursOn(routine, date, zone)) return null
        if (routine.id in completionDao.doneOn(date.toEpochDay())) return null
        // Exceção pontual (T20) aplicada enquanto o aviso estava adiado: um SKIP cancela a ocorrência
        // e uma remarcação para OUTRO dia a tira daqui — nos dois casos o aviso adiado virou obsoleto,
        // então não reexibe. Mas a PRÓPRIA ocorrência remarcada neste dia (só mudou o minuto) continua
        // acontecendo hoje: "Adiar" nela tem de manter o lembrete, senão o aviso some em silêncio.
        val override = occurrenceOverride(routineId, date.toEpochDay())
        if (override != null && !isSameDayReschedule(override, date.toEpochDay())) return null
        return routine
    }

    /** Remarcação da ocorrência DESTE dia sem trocar de dia (só o minuto): continua valendo hoje. */
    private fun isSameDayReschedule(override: RoutineOverride, epochDay: Long): Boolean =
        override.type == RoutineOverrideType.RESCHEDULE &&
            (override.newEpochDay == null || override.newEpochDay == epochDay)

    /** Exceções de UMA rotina, para o aviso montar o horário efetivo (honra o RESCHEDULE do dia). */
    suspend fun overridesFor(routineId: Long): List<RoutineOverride> =
        overrideDao.all().filter { it.routineId == routineId }

    // --- Exceções pontuais (T20): pular/remarcar UMA ocorrência sem desligar a rotina. ---

    /** A exceção daquele dia, se houver (para a tela de exceção mostrar o estado atual). */
    suspend fun occurrenceOverride(routineId: Long, epochDay: Long): RoutineOverride? =
        overrideDao.all().firstOrNull { it.routineId == routineId && it.epochDay == epochDay }

    /**
     * Pular a ocorrência daquele dia: a rotina não acontece ali, mas continua ativa. Uma exceção por
     * dia (índice único): o `SKIP` substitui a que houver. Devolve a exceção ANTERIOR do dia (ex.: uma
     * remarcação; `null` se não havia), lida no mesmo [change], para o Desfazer da mensagem recolocá-la
     * ([restoreOccurrenceException]). Mesmo caminho para a tela de exceção, a notificação e a Hoje.
     */
    suspend fun skipOccurrence(routineId: Long, epochDay: Long): RoutineOverride? = change {
        val previous = occurrenceOverride(routineId, epochDay)
        overrideDao.insert(RoutineOverride(routineId = routineId, epochDay = epochDay, type = RoutineOverrideType.SKIP))
        if (epochDay == LocalDate.now().toEpochDay()) notifications.cancel(routineId)
        previous?.takeUnless { it.type == RoutineOverrideType.SKIP }
    }

    /**
     * Desfazer da mensagem logo depois de pular (P5): devolve o dia ao que era ANTES do [skipOccurrence]
     * — tira o `SKIP` e recoloca [previous] (a exceção que ele substituiu; `null` = nenhuma). Só age se
     * o dia ainda está pulado: se outra alteração veio no meio, ela vence e nada muda. O "Desfazer
     * pular" do bloco, mais tarde, é o [clearOccurrenceException] (só tira o pular).
     */
    suspend fun restoreOccurrenceException(routineId: Long, epochDay: Long, previous: RoutineOverride?): Unit = change {
        val current = occurrenceOverride(routineId, epochDay)
        if (current?.type != RoutineOverrideType.SKIP) return@change
        if (previous != null && previous.routineId == routineId && previous.epochDay == epochDay) {
            // Mesmo dia (índice único): substitui o SKIP pela anterior, com o id dela.
            overrideDao.insert(previous)
        } else {
            overrideDao.delete(current)
        }
    }

    /** Remarcar a ocorrência daquele dia para outro minuto e/ou outro dia. */
    suspend fun rescheduleOccurrence(routineId: Long, epochDay: Long, newStartMinute: Int?, newEpochDay: Long?): Unit = change {
        overrideDao.insert(
            RoutineOverride(
                routineId = routineId,
                epochDay = epochDay,
                type = RoutineOverrideType.RESCHEDULE,
                newStartMinute = newStartMinute,
                newEpochDay = newEpochDay,
            ),
        )
    }

    /** Remover a exceção daquele dia: a ocorrência volta ao horário normal da rotina. */
    suspend fun clearOccurrenceException(routineId: Long, epochDay: Long): Unit = change {
        val existing = occurrenceOverride(routineId, epochDay) ?: return@change
        overrideDao.delete(existing)
    }

    // --- Catch-up do aviso de bloco (ADR-0003, 4.3.1) -------------------------------------------

    /**
     * Entrega os avisos de bloco vencidos e ainda não processados, agenda o próximo início
     * estritamente depois de agora e só então grava a marca. Chamado pelo alarme
     * ([com.kalendrys.app.reminder.RoutineAlarmReceiver]) e pelos pontos de entrada do app (início de
     * processo, boot, volta ao primeiro plano). Quem chega primeiro entrega; quem chega depois
     * encontra a janela vazia.
     *
     * [nowOverride] só para quem já tem o "agora" (testes, ponto de entrada com um único `now`). Sem
     * ele, o relógio é lido DENTRO do Mutex: lido antes, duas chamadas quase simultâneas podiam entrar
     * em ordem trocada e a segunda gravar a marca para trás.
     */
    suspend fun catchUp(nowOverride: Long? = null) = mutex.withLock {
        catchUpLocked(nowOverride ?: clock(), schedule = true)
    }

    /**
     * Depois de restaurar um backup: a marca passa a ser agora e o próximo início é agendado, sem
     * avisar nada do passado do backup.
     */
    suspend fun establishBaseline(nowOverride: Long? = null) = mutex.withLock {
        val now = nowOverride ?: clock()
        withContext(NonCancellable) {
            scheduleLocked(now)
            mark.set(now)
        }
    }

    /**
     * Alteração de rotina: catch-up com a configuração antiga, a gravação e, depois, o agendamento
     * com a nova, tudo sob o mesmo Mutex e com o mesmo "agora".
     *
     * Tudo em NonCancellable: o "Salvar" do editor seguido de voltar na hora cancela o escopo do
     * ViewModel, e cortado entre gravar e agendar o alarme antigo ficaria valendo. Uma falha na
     * ENTREGA do catch-up é registrada e não impede a gravação: perder o salvar da rotina por causa
     * de uma notificação seria pior. No [catchUp] dos pontos de entrada a falha continua propagando.
     */
    private suspend fun <T> change(write: suspend () -> T): T = mutex.withLock {
        val now = clock()
        withContext(NonCancellable) {
            catchUpLocked(now, schedule = false, tolerateDeliveryFailure = true)
            val result = write()
            scheduleLocked(now)
            result
        }
    }

    /**
     * Plano → entrega → agenda (se [schedule]) → grava a marca. Substituir o alarme só depois de
     * entregar é o que impede o pulo. No [change] o agendamento fica para depois da gravação: a marca
     * sobe antes, mas o alarme pendente continua o antigo até lá. Marca ausente (primeira execução):
     * não entrega nada, só estabelece a marca.
     */
    private suspend fun catchUpLocked(now: Long, schedule: Boolean, tolerateDeliveryFailure: Boolean = false) {
        val lastSeen = mark.get()
        val due = if (lastSeen == null) emptyList() else plan(lastSeen, now)
        // Da entrega até gravar a marca, sem cancelamento: cortado no meio, o aviso já mostrado seria
        // reexibido na próxima execução.
        withContext(NonCancellable) {
            due.forEach { block ->
                try {
                    deliverDueBlocks(block.routines, block.date)
                } catch (error: Exception) {
                    if (error is CancellationException || !tolerateDeliveryFailure) throw error
                    Log.w(TAG, "Aviso de bloco não entregue; a alteração segue", error)
                }
            }
            if (schedule) scheduleLocked(now)
            mark.set(now)
        }
    }

    private suspend fun plan(lastSeen: Long, now: Long): List<RoutineCatchUp.Due> {
        val zone = zone()
        val days = RoutineCatchUp.days(lastSeen, now, zone)
        if (days.isEmpty()) return emptyList()
        val doneByDay = days.associateWith { completionDao.doneOn(it.toEpochDay()).toSet() }
        return RoutineCatchUp.plan(routineDao.active(), overridesByRoutine(), doneByDay, lastSeen, now, zone)
    }

    /**
     * Agenda o próximo início estritamente depois de [now], com os dados lidos agora (dentro do Mutex).
     * Passa as rotinas concluídas no dia desse início: com todas as que avisam concluídas, o alarme fica
     * silencioso (ainda redesenha os widgets), a mesma regra da entrega (ADR-0002, 14.3).
     */
    private suspend fun scheduleLocked(now: Long) {
        val routines = routineDao.active()
        val after = startingPoint(now)
        val overrides = overridesByRoutine()
        val next = RoutineSchedule.nextStart(routines, after, zone(), overrides)
        val done = next?.let { completionDao.doneOn(it.at.toLocalDate().toEpochDay()).toSet() }.orEmpty()
        alarms.scheduleNext(routines, after, overrides, done)
    }

    /**
     * Próximo início de bloco que AVISA (rotina com `remind`) estritamente depois de [now], em epoch
     * millis; `null` sem nenhum. É o próximo aviso previsto, não uma leitura do sistema: o
     * agendamento é em cadeia (cada alarme agenda o seguinte, e um início sem aviso é só um alarme
     * silencioso que redesenha os widgets), então este horário pode ainda não estar no AlarmManager.
     * Pula o início em que todas as rotinas que avisam já estão concluídas no dia: o catch-up o
     * descartaria ([RoutineCatchUp.isDiscarded]), então nada chegaria. Avança no máximo
     * [MAX_DISCARDED_STARTS] inícios; além disso, `null`.
     * Só leitura: não passa pelo Mutex, não agenda nada e não mexe na marca do catch-up.
     */
    suspend fun nextRemindingStart(now: Long = clock()): Long? {
        val zone = zone()
        val reminding = routineDao.active().filter { it.remind }
        val overrides = overridesByRoutine()
        var after = startingPoint(now)
        repeat(MAX_DISCARDED_STARTS) {
            val start = RoutineSchedule.nextStart(reminding, after, zone, overrides) ?: return null
            val done = completionDao.doneOn(start.at.toLocalDate().toEpochDay()).toSet()
            if (start.routines.any { !RoutineCatchUp.isDiscarded(it, done) }) return start.at.atZone(zone).toInstant().toEpochMilli()
            after = start.at
        }
        return null
    }

    /** Ponto de partida do próximo início: o mesmo para o agendamento e para o diagnóstico. */
    private fun startingPoint(now: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone())

    /** Exceções agrupadas por rotina, para o [RoutineSchedule] honrar skip/remarcação. */
    private suspend fun overridesByRoutine(): Map<Long, List<RoutineOverride>> =
        overrideDao.all().groupBy { it.routineId }

    private companion object {
        const val TAG = "RoutineRepository"

        /** Limite de inícios pulados no [nextRemindingStart] (concluídos no dia); folga para um dia cheio de blocos. */
        const val MAX_DISCARDED_STARTS = 64
    }
}
