package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.RoutineCompletionDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
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
    private val alarms: RoutineAlarms,
    private val notifications: RoutineNotifications,
) {

    /** Todas as rotinas, inclusive as excluídas; quem decide o que aparece em cada dia é o [RoutineSchedule]. */
    val routines: Flow<List<Routine>> = routineDao.observeAll()

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

    /** Refaz o alarme do próximo início (mudança nas rotinas, reinício, mudança de fuso ou relógio). */
    suspend fun scheduleNextAlarm(now: LocalDateTime = LocalDateTime.now()) {
        alarms.scheduleNext(routineDao.active(), now)
    }

    /**
     * Chamado quando o alarme toca: devolve os blocos daquele dia que começaram entre o horário
     * agendado e agora (o alarme pode atrasar), com aviso ligado e ainda não feitos. Já deixa
     * agendado o próximo início.
     */
    suspend fun onAlarm(epochDay: Long, scheduledMinute: Int, now: LocalDateTime = LocalDateTime.now()): List<Routine> {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.ofEpochDay(epochDay)
        val untilMinute = if (now.toLocalDate() == date) now.hour * 60 + now.minute else MINUTES_PER_DAY
        val active = routineDao.active()
        val done = completionDao.doneOn(epochDay).toSet()
        val due = active.filter {
            it.remind &&
                it.id !in done &&
                it.startMinute in scheduledMinute..untilMinute &&
                RoutineSchedule.occursOn(it, date, zone)
        }
        // Estritamente depois do horário que tocou (ou de agora, se atrasou): não repete o mesmo início.
        val scheduledAt = date.atStartOfDay().plusMinutes(scheduledMinute.toLong())
        alarms.scheduleNext(active, maxOf(now, scheduledAt))
        return due
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
