package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Quando os blocos de rotina acontecem.
 *
 * Ao contrário dos lembretes de tarefa (que guardam um instante absoluto), uma rotina segue o
 * relógio local: o treino das 07:00 continua às 07:00 depois de uma mudança de fuso ou do
 * horário de verão. Por isso os horários saem sempre de `date.atTime(...)` no fuso atual.
 */
object RoutineSchedule {

    /** Um horário de início e as rotinas que começam nele. */
    data class Start(val at: LocalDateTime, val routines: List<Routine>)

    fun createdOn(routine: Routine, zone: ZoneId): LocalDate = dateOf(routine.createdAt, zone)

    fun archivedOn(routine: Routine, zone: ZoneId): LocalDate? = routine.archivedAt?.let { dateOf(it, zone) }

    /** Se o bloco acontece em [date]: dia marcado, a partir do dia da criação e antes do da exclusão. */
    fun occursOn(routine: Routine, date: LocalDate, zone: ZoneId): Boolean {
        if (!RoutineDays.contains(routine.daysOfWeek, date.dayOfWeek)) return false
        if (date.isBefore(createdOn(routine, zone))) return false
        val archived = archivedOn(routine, zone)
        return archived == null || date.isBefore(archived)
    }

    fun startOn(routine: Routine, date: LocalDate): LocalDateTime =
        date.atStartOfDay().plusMinutes(routine.startMinute.toLong())

    /** Próximo início da rotina estritamente depois de [after]; `null` se não há mais nenhum. */
    fun nextStart(routine: Routine, after: LocalDateTime, zone: ZoneId): LocalDateTime? {
        for (offset in 0L..DAYS_TO_SEARCH) {
            val date = after.toLocalDate().plusDays(offset)
            if (!occursOn(routine, date, zone)) continue
            val start = startOn(routine, date)
            if (start.isAfter(after)) return start
        }
        return null
    }

    /** O próximo horário em que algum bloco começa, com todas as rotinas que começam nele. */
    fun nextStart(routines: List<Routine>, after: LocalDateTime, zone: ZoneId): Start? {
        val starts = routines.mapNotNull { routine -> nextStart(routine, after, zone)?.let { routine to it } }
        val first = starts.minOfOrNull { it.second } ?: return null
        return Start(first, starts.filter { it.second == first }.map { it.first }.sortedBy { it.id })
    }

    private fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    // Uma semana inteira mais um dia cobre qualquer combinação de dias.
    private const val DAYS_TO_SEARCH = 7L
}
