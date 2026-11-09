package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.RoutineCompletionDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.dao.RoutineOverrideDao
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.reminder.RoutineNotifications
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Fonte única das rotinas: banco, alarme do próximo início e notificações sempre em sincronia. */
class RoutineRepository(
    private val routineDao: RoutineDao,
    private val completionDao: RoutineCompletionDao,
    private val overrideDao: RoutineOverrideDao,
    private val alarms: RoutineAlarms,
    private val notifications: RoutineNotifications,
) {

    /** Todas as rotinas, inclusive as excluídas; quem decide o que aparece em cada dia é o [RoutineSchedule]. */
    val routines: Flow<List<Routine>> = routineDao.observeAll()

    /**
     * Rotinas que entram no retrato do dia (resumo/widgets): as pausadas ficam de fora. A lista de
     * gestão ([routines]) continua mostrando as pausadas, para dar pra retomá-las.
     */
    val scheduledRoutines: Flow<List<Routine>> = routineDao.observeScheduled()

    fun observeRoutine(id: Long): Flow<Routine?> = routineDao.observeById(id)

    /** Conclusões das ocorrências entre [start] (inclusive) e [endExclusive]. */
    fun observeCompletionsBetween(start: LocalDate, endExclusive: LocalDate): Flow<List<RoutineCompletion>> =
        completionDao.observeBetween(start.toEpochDay(), endExclusive.toEpochDay())

    suspend fun add(routine: Routine): Routine {
        val saved = routine.copy(id = routineDao.insert(routine))
        scheduleNextAlarm()
        return saved
    }

    suspend fun update(routine: Routine) {
        routineDao.update(routine)
        scheduleNextAlarm()
    }

    /** "Excluir": some de hoje em diante, mas os dias passados mantêm o bloco e a conclusão. */
    suspend fun archive(routine: Routine, now: Long = System.currentTimeMillis()) {
        routineDao.update(routine.copy(archivedAt = now))
        notifications.cancel(routine.id)
        scheduleNextAlarm()
    }

    /**
     * "Pausar": a rotina para de agendar e avisar, mas a configuração e o histórico ficam
     * intactos. Distinta de arquivar (excluir): dá pra retomar depois. Reagenda sem ela e tira
     * da tela qualquer aviso já mostrado.
     */
    suspend fun pause(routine: Routine, now: Long = System.currentTimeMillis()) {
        routineDao.update(routine.copy(pausedAt = now))
        notifications.cancel(routine.id)
        scheduleNextAlarm()
    }

    /** "Retomar": a rotina volta a agendar/avisar com a configuração e o histórico preservados. */
    suspend fun resume(routine: Routine) {
        routineDao.update(routine.copy(pausedAt = null))
        scheduleNextAlarm()
    }

    /**
     * Idempotente: tocar duas vezes em "Concluir" (widget, notificação) não desfaz. Confere se a
     * rotina ainda existe, porque a notificação pode ser de uma rotina já excluída.
     */
    suspend fun complete(routineId: Long, date: LocalDate) {
        if (routineDao.byId(routineId) == null) return
        completionDao.insert(RoutineCompletion(routineId = routineId, epochDay = date.toEpochDay()))
        if (date == LocalDate.now()) notifications.cancel(routineId)
    }

    suspend fun uncomplete(routineId: Long, date: LocalDate) {
        completionDao.delete(routineId, date.toEpochDay())
    }

    // --- Exceções pontuais (T20): pular/remarcar UMA ocorrência sem desligar a rotina. ---

    /** A exceção daquele dia, se houver (para a tela de exceção mostrar o estado atual). */
    suspend fun occurrenceOverride(routineId: Long, epochDay: Long): RoutineOverride? =
        overrideDao.all().firstOrNull { it.routineId == routineId && it.epochDay == epochDay }

    /** Pular a ocorrência daquele dia: a rotina não acontece ali, mas continua ativa. */
    suspend fun skipOccurrence(routineId: Long, epochDay: Long) {
        overrideDao.insert(RoutineOverride(routineId = routineId, epochDay = epochDay, type = RoutineOverrideType.SKIP))
        if (epochDay == LocalDate.now().toEpochDay()) notifications.cancel(routineId)
        scheduleNextAlarm()
    }

    /** Remarcar a ocorrência daquele dia para outro minuto e/ou outro dia. */
    suspend fun rescheduleOccurrence(routineId: Long, epochDay: Long, newStartMinute: Int?, newEpochDay: Long?) {
        overrideDao.insert(
            RoutineOverride(
                routineId = routineId,
                epochDay = epochDay,
                type = RoutineOverrideType.RESCHEDULE,
                newStartMinute = newStartMinute,
                newEpochDay = newEpochDay,
            ),
        )
        scheduleNextAlarm()
    }

    /** Remover a exceção daquele dia: a ocorrência volta ao horário normal da rotina. */
    suspend fun clearOccurrenceException(routineId: Long, epochDay: Long) {
        val existing = occurrenceOverride(routineId, epochDay) ?: return
        overrideDao.delete(existing)
        scheduleNextAlarm()
    }

    /** Refaz o alarme do próximo início (mudança nas rotinas, reinício, mudança de fuso ou relógio). */
    suspend fun scheduleNextAlarm(now: LocalDateTime = LocalDateTime.now()) {
        alarms.scheduleNext(routineDao.active(), now, overridesByRoutine())
    }

    /**
     * Chamado quando o alarme toca: devolve os blocos daquele dia que começaram entre o horário
     * agendado e agora (o alarme pode atrasar), com aviso ligado e ainda não feitos. Já deixa
     * agendado o próximo início. Honra as exceções: um `SKIP`/remarcação para outro dia some da
     * lista, e uma remarcação de minuto usa o horário efetivo.
     */
    suspend fun onAlarm(epochDay: Long, scheduledMinute: Int, now: LocalDateTime = LocalDateTime.now()): List<Routine> {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.ofEpochDay(epochDay)
        val untilMinute = if (now.toLocalDate() == date) now.hour * 60 + now.minute else MINUTES_PER_DAY
        val active = routineDao.active()
        val overrides = overridesByRoutine()
        val done = completionDao.doneOn(epochDay).toSet()
        val due = active.filter { routine ->
            val ov = overrides[routine.id].orEmpty()
            if (!routine.remind || routine.id in done || !RoutineSchedule.occursOn(routine, date, zone, ov)) {
                return@filter false
            }
            val start = RoutineSchedule.startOn(routine, date, ov)
            val minute = start.hour * 60 + start.minute
            minute in scheduledMinute..untilMinute
        }
        // Estritamente depois do horário que tocou (ou de agora, se atrasou): não repete o mesmo início.
        val scheduledAt = date.atStartOfDay().plusMinutes(scheduledMinute.toLong())
        alarms.scheduleNext(active, maxOf(now, scheduledAt), overrides)
        return due
    }

    /** Exceções agrupadas por rotina, para o [RoutineSchedule] honrar skip/remarcação. */
    private suspend fun overridesByRoutine(): Map<Long, List<RoutineOverride>> =
        overrideDao.all().groupBy { it.routineId }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
