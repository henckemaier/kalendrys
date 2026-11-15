package com.kalendrys.app.data.repository

import android.util.Log
import com.kalendrys.app.data.local.dao.RoutineCompletionDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.dao.RoutineOverrideDao
import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.domain.RoutineCatchUp
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
 * [com.kalendrys.app.data.settings.SettingsRepository]; nos testes, [InMemoryRoutineCatchUpMark].
 */
interface RoutineCatchUpMark {
    suspend fun get(): Long?

    suspend fun set(millis: Long)
}

/** Marca em memória (padrão do repositório, para os testes). Começa ausente, como logo após atualizar. */
class InMemoryRoutineCatchUpMark(private var value: Long? = null) : RoutineCatchUpMark {
    override suspend fun get(): Long? = value

    override suspend fun set(millis: Long) {
        value = millis
    }
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
    private val deliverDueBlocks: suspend (List<Routine>, LocalDate) -> Unit = { _, _ -> },
    private val mark: RoutineCatchUpMark = InMemoryRoutineCatchUpMark(),
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

    suspend fun add(routine: Routine): Routine = change {
        routine.copy(id = routineDao.insert(routine))
    }

    suspend fun update(routine: Routine): Unit = change {
        routineDao.update(routine)
    }

    /** "Excluir": some de hoje em diante, mas os dias passados mantêm o bloco e a conclusão. */
    suspend fun archive(routine: Routine, now: Long = System.currentTimeMillis()): Unit = change {
        routineDao.update(routine.copy(archivedAt = now))
        notifications.cancel(routine.id)
    }

    /**
     * "Pausar": a rotina para de agendar e avisar, mas a configuração e o histórico ficam
     * intactos. Distinta de arquivar (excluir): dá pra retomar depois. Reagenda sem ela e tira
     * da tela qualquer aviso já mostrado.
     */
    suspend fun pause(routine: Routine, now: Long = System.currentTimeMillis()): Unit = change {
        routineDao.update(routine.copy(pausedAt = now))
        notifications.cancel(routine.id)
    }

    /** "Retomar": a rotina volta a agendar/avisar com a configuração e o histórico preservados. */
    suspend fun resume(routine: Routine): Unit = change {
        routineDao.update(routine.copy(pausedAt = null))
    }

    /**
     * Idempotente: tocar duas vezes em "Concluir" (widget, notificação) não desfaz. Confere se a
     * rotina ainda existe, porque a notificação pode ser de uma rotina já excluída.
     *
     * Concluir a ocorrência de HOJE de uma rotina (não arquivada) com lista anexada zera as marcações
     * da lista (ADR-0001, P4), de forma reversível pelo [uncomplete]. Mora aqui, e não na tela, para
     * valer também no widget e na ação "Concluir" da notificação. Só reinicia quando a conclusão é
     * nova: um segundo toque não sobrescreve as marcações guardadas pelo primeiro.
     */
    suspend fun complete(routineId: Long, date: LocalDate, today: LocalDate = LocalDate.now()): Unit = change {
        val routine = routineDao.byId(routineId) ?: return@change
        val inserted = completionDao.insert(RoutineCompletion(routineId = routineId, epochDay = date.toEpochDay())) != -1L
        if (date == today) notifications.cancel(routineId)
        val listId = routine.shoppingListId
        if (inserted && listId != null && routine.archivedAt == null && ShoppingReset.routineResets(date, today)) {
            shoppingItemDao.resetPicked(listId, ShoppingReset.routineKey(routineId, date.toEpochDay()))
        }
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

    /** Pular a ocorrência daquele dia: a rotina não acontece ali, mas continua ativa. */
    suspend fun skipOccurrence(routineId: Long, epochDay: Long): Unit = change {
        overrideDao.insert(RoutineOverride(routineId = routineId, epochDay = epochDay, type = RoutineOverrideType.SKIP))
        if (epochDay == LocalDate.now().toEpochDay()) notifications.cancel(routineId)
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

    /** Agenda o próximo início estritamente depois de [now], com os dados lidos agora (dentro do Mutex). */
    private suspend fun scheduleLocked(now: Long) {
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone())
        alarms.scheduleNext(routineDao.active(), after, overridesByRoutine())
    }

    /** Exceções agrupadas por rotina, para o [RoutineSchedule] honrar skip/remarcação. */
    private suspend fun overridesByRoutine(): Map<Long, List<RoutineOverride>> =
        overrideDao.all().groupBy { it.routineId }

    private companion object {
        const val TAG = "RoutineRepository"
    }
}
