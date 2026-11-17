package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Plano do catch-up dos avisos de início de bloco (ADR-0003, 4.3.1): quais blocos avisar agora,
 * entre a última execução ([lastSeen]) e [now]. Lógica pura, testada na JVM; quem entrega, agenda
 * e grava a marca é o [com.kalendrys.app.data.repository.RoutineRepository.catchUp].
 *
 * Um bloco entra quando:
 * - o aviso está ligado ([Routine.remind]);
 * - ocorre no dia, honrando `SKIP` e remarcação de dia e de minuto ([RoutineSchedule]);
 * - não está concluído naquele dia;
 * - o início efetivo está em `(lastSeen, now]` (com `now <= lastSeen`, relógio voltou, nada entra);
 * - `now - início <= tolerância` ([ReminderCatchUp.LATE_TOLERANCE_MINUTES]);
 * - se tem duração, ainda não terminou (`now < fim`): é aviso de início, depois do fim não serve.
 *
 * Fora da tolerância o bloco é só descartado: rotina não tem aviso agregado de perdidos. A janela
 * pode cruzar a meia-noite, então os dias avaliados são os de [days] (ontem e hoje, no máximo).
 */
object RoutineCatchUp {

    /** Blocos a avisar num dia, em ordem de início (e de id, no mesmo minuto). */
    data class Due(val date: LocalDate, val routines: List<Routine>)

    /**
     * Dias que podem ter início dentro da janela útil `[max(lastSeen, now - tolerância), now]`. Quem
     * chama usa isto para ler as conclusões só desses dias. Vazio quando o relógio voltou.
     */
    fun days(
        lastSeen: Long,
        now: Long,
        zone: ZoneId,
        toleranceMinutes: Long = ReminderCatchUp.LATE_TOLERANCE_MINUTES,
    ): List<LocalDate> {
        if (now <= lastSeen) return emptyList()
        val lower = maxOf(lastSeen, now - toleranceMinutes * MS_PER_MINUTE)
        val first = dateOf(lower, zone)
        val last = dateOf(now, zone)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }

    /**
     * [routines]: as ativas (nem arquivadas nem pausadas). [overridesByRoutine]: exceções por rotina.
     * [doneByDay]: ids das rotinas concluídas em cada dia de [days].
     */
    fun plan(
        routines: List<Routine>,
        overridesByRoutine: Map<Long, List<RoutineOverride>>,
        doneByDay: Map<LocalDate, Set<Long>>,
        lastSeen: Long,
        now: Long,
        zone: ZoneId,
        toleranceMinutes: Long = ReminderCatchUp.LATE_TOLERANCE_MINUTES,
    ): List<Due> {
        val toleranceMs = toleranceMinutes * MS_PER_MINUTE
        return days(lastSeen, now, zone, toleranceMinutes).mapNotNull { date ->
            val done = doneByDay[date].orEmpty()
            val due = routines.mapNotNull { routine ->
                if (isDiscarded(routine, done)) return@mapNotNull null
                val overrides = overridesByRoutine[routine.id].orEmpty()
                if (!RoutineSchedule.occursOn(routine, date, zone, overrides)) return@mapNotNull null
                val start = RoutineSchedule.startOn(routine, date, overrides).atZone(zone).toInstant().toEpochMilli()
                val inWindow = start > lastSeen && start <= now && now - start <= toleranceMs
                val ended = routine.durationMinutes > 0 && now >= start + routine.durationMinutes * MS_PER_MINUTE
                if (inWindow && !ended) routine to start else null
            }
            due.sortedWith(compareBy({ it.second }, { it.first.id }))
                .map { it.first }
                .takeIf { it.isNotEmpty() }
                ?.let { Due(date, it) }
        }
    }

    /**
     * O aviso de início de [routine] num dia é descartado: o aviso está desligado ou a rotina já foi
     * concluída nesse dia ([doneThatDay]: ids das concluídas no dia). O [plan] e o diagnóstico de
     * alarmes ([com.kalendrys.app.data.repository.RoutineRepository.nextRemindingStart]) usam a
     * mesma regra.
     */
    fun isDiscarded(routine: Routine, doneThatDay: Set<Long>): Boolean = !routine.remind || routine.id in doneThatDay

    private fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private const val MS_PER_MINUTE = 60_000L
}
