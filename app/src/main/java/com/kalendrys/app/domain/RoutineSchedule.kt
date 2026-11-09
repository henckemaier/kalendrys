package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Quando os blocos de rotina acontecem.
 *
 * Ao contrário dos lembretes de tarefa (que guardam um instante absoluto), uma rotina segue o
 * relógio local: o treino das 07:00 continua às 07:00 depois de uma mudança de fuso ou do
 * horário de verão. Por isso os horários saem sempre de `date.atTime(...)` no fuso atual.
 *
 * Recorrência rica (T19), a partir dos campos da própria rotina:
 * - **a cada N semanas** ([Routine.weekInterval]): só nas semanas cuja paridade bate com a semana
 *   âncora (a de [Routine.startEpochDay], ou a da criação se não houver início definido).
 * - **janela de vigência**: não ocorre antes de [Routine.startEpochDay] nem depois de
 *   [Routine.endEpochDay] (rotina temporária).
 *
 * Exceções pontuais (T20), lidas de uma lista de [RoutineOverride] daquela rotina (vazia = sem
 * exceção): `SKIP` remove a ocorrência do dia; `RESCHEDULE` a remarca para outro minuto e/ou dia.
 */
object RoutineSchedule {

    /** Um horário de início e as rotinas que começam nele. */
    data class Start(val at: LocalDateTime, val routines: List<Routine>)

    fun createdOn(routine: Routine, zone: ZoneId): LocalDate = dateOf(routine.createdAt, zone)

    fun archivedOn(routine: Routine, zone: ZoneId): LocalDate? = routine.archivedAt?.let { dateOf(it, zone) }

    /**
     * Se o bloco acontece em [date], já considerando a recorrência rica e as [overrides] daquela
     * rotina: dia marcado, dentro da janela/paridade, da criação até a exclusão, menos um `SKIP` e
     * mais um `RESCHEDULE` que caia neste dia.
     */
    fun occursOn(
        routine: Routine,
        date: LocalDate,
        zone: ZoneId,
        overrides: List<RoutineOverride> = emptyList(),
    ): Boolean {
        val epochDay = date.toEpochDay()
        // Um RESCHEDULE que aponta para ESTE dia traz a ocorrência para cá, mesmo fora do dia marcado.
        if (overrides.any { it.type == RoutineOverrideType.RESCHEDULE && it.newEpochDay == epochDay }) return true
        if (!baseOccursOn(routine, date, zone)) return false
        val override = overrides.firstOrNull { it.epochDay == epochDay } ?: return true
        return when (override.type) {
            RoutineOverrideType.SKIP -> false
            // Remarcada para OUTRO dia some daqui; mesmo dia (ou só troca de minuto) continua ocorrendo.
            RoutineOverrideType.RESCHEDULE -> override.newEpochDay == null || override.newEpochDay == epochDay
            else -> true // tipo desconhecido: ignora a exceção em vez de sumir com a ocorrência
        }
    }

    /**
     * Horário de início do bloco em [date], honrando um `RESCHEDULE` (novo minuto e/ou vindo de
     * outro dia). Sem exceção, é o [Routine.startMinute] no dia.
     */
    fun startOn(
        routine: Routine,
        date: LocalDate,
        overrides: List<RoutineOverride> = emptyList(),
    ): LocalDateTime {
        val epochDay = date.toEpochDay()
        // Ocorrência remarcada DE outro dia PARA este: usa o minuto da exceção (ou o da rotina).
        val inbound = overrides.firstOrNull { it.type == RoutineOverrideType.RESCHEDULE && it.newEpochDay == epochDay }
        if (inbound != null) return atMinute(date, inbound.newStartMinute ?: routine.startMinute)
        // Remarcação no próprio dia: só muda o minuto.
        val sameDay = overrides.firstOrNull { it.epochDay == epochDay && it.type == RoutineOverrideType.RESCHEDULE }
        if (sameDay != null) return atMinute(date, sameDay.newStartMinute ?: routine.startMinute)
        return atMinute(date, routine.startMinute)
    }

    /** Próximo início da rotina estritamente depois de [after]; `null` se não há mais nenhum. */
    fun nextStart(
        routine: Routine,
        after: LocalDateTime,
        zone: ZoneId,
        overrides: List<RoutineOverride> = emptyList(),
    ): LocalDateTime? {
        var best: LocalDateTime? = null
        for (offset in 0L..daysToSearch(routine)) {
            val date = after.toLocalDate().plusDays(offset)
            if (!occursOn(routine, date, zone, overrides)) continue
            val start = startOn(routine, date, overrides)
            // Mínimo após [after]: um RESCHEDULE pode reordenar os dias, então não basta o primeiro.
            if (start.isAfter(after) && (best == null || start.isBefore(best))) best = start
        }
        return best
    }

    /** O próximo horário em que algum bloco começa, com todas as rotinas que começam nele. */
    fun nextStart(
        routines: List<Routine>,
        after: LocalDateTime,
        zone: ZoneId,
        overridesByRoutine: Map<Long, List<RoutineOverride>> = emptyMap(),
    ): Start? {
        val starts = routines.mapNotNull { routine ->
            nextStart(routine, after, zone, overridesByRoutine[routine.id].orEmpty())?.let { routine to it }
        }
        val first = starts.minOfOrNull { it.second } ?: return null
        return Start(first, starts.filter { it.second == first }.map { it.first }.sortedBy { it.id })
    }

    /** Regra base (sem exceções): dia marcado, janela de vigência, paridade de semana, criação/exclusão. */
    private fun baseOccursOn(routine: Routine, date: LocalDate, zone: ZoneId): Boolean {
        if (!RoutineDays.contains(routine.daysOfWeek, date.dayOfWeek)) return false
        if (date.isBefore(createdOn(routine, zone))) return false
        val archived = archivedOn(routine, zone)
        if (archived != null && !date.isBefore(archived)) return false
        startBound(routine)?.let { if (date.isBefore(it)) return false }
        endBound(routine)?.let { if (date.isAfter(it)) return false }
        return inWeekParity(routine, date, zone)
    }

    /** Paridade "a cada N semanas", contada em semanas inteiras (segunda a segunda) a partir da âncora. */
    private fun inWeekParity(routine: Routine, date: LocalDate, zone: ZoneId): Boolean {
        val interval = routine.weekInterval
        if (interval <= 1) return true
        val anchorMonday = mondayOf(startBound(routine) ?: createdOn(routine, zone))
        val weeks = ChronoUnit.WEEKS.between(anchorMonday, mondayOf(date))
        return Math.floorMod(weeks, interval.toLong()) == 0L
    }

    private fun startBound(routine: Routine): LocalDate? = routine.startEpochDay?.let(LocalDate::ofEpochDay)

    private fun endBound(routine: Routine): LocalDate? = routine.endEpochDay?.let(LocalDate::ofEpochDay)

    private fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun atMinute(date: LocalDate, minute: Int): LocalDateTime =
        date.atStartOfDay().plusMinutes(minute.toLong())

    private fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    // Uma semana inteira por intervalo (cobre semanas puladas pela paridade) mais um dia da semana.
    private fun daysToSearch(routine: Routine): Long = 7L * routine.weekInterval.coerceAtLeast(1) + 7L
}
