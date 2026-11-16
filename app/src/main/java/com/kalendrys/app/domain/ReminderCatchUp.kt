package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Plano do catch-up de lembretes (ADR-0003): o que entregar agora e quantos lembretes se perderam,
 * entre a última execução ([lastSeen]) e [now]. Lógica pura, testada na JVM; quem mostra,
 * reagenda e grava a marca é o [com.kalendrys.app.reminder.ReminderCatchUpRunner].
 *
 * - Janela `(lastSeen, now]`: aberta embaixo (o instante [lastSeen] já foi processado), fechada em
 *   cima. Com `now <= lastSeen` (relógio voltou) a janela é vazia.
 * - Atrasada: `now - ocorrência <= tolerância` ([LATE_TOLERANCE_MINUTES]); sai como lembrete
 *   normal. Perdida: acima da tolerância; entra no aviso agregado.
 * - Por tarefa, só a ocorrência MAIS RECENTE da janela pode sair como lembrete; as demais contam
 *   como perdidas se estiverem fora da tolerância.
 * - Fora do plano: tarefa sem lembrete, tarefa única encerrada ([TaskSchedule.isFinished]) e a
 *   ocorrência de tarefa repetida cujo dia já tem conclusão.
 *
 * Substitui o antigo `MissedReminders` e passa intervalo, unidade e dias da semana para
 * [ReminderRecurrence.occurrencesBetween] (a repetição personalizada era contada como diária).
 */
object ReminderCatchUp {

    /** Tolerância para tratar uma ocorrência vencida como atrasada (e não perdida). Decisão do usuário. */
    const val LATE_TOLERANCE_MINUTES = 60L

    /** Uma ocorrência de lembrete: a tarefa e o instante em que deveria ter tocado. */
    data class Occurrence(val taskId: Long, val firedAt: Long)

    /** [late]: a entregar agora, em ordem de horário. [missedCount]: para o aviso "N lembretes perdidos". */
    data class Plan(val late: List<Occurrence>, val missedCount: Int) {
        val isEmpty: Boolean get() = late.isEmpty() && missedCount == 0

        companion object {
            val EMPTY = Plan(emptyList(), 0)
        }
    }

    /**
     * [lastCompletions]: última conclusão de cada tarefa (taskId → epoch millis), para a guarda de
     * tarefa única encerrada. [completionDays]: dias (no [zone]) com conclusão, por tarefa, ao
     * menos os dias da janela; para a guarda de repetida concluída no dia da ocorrência.
     */
    fun plan(
        tasks: List<Task>,
        lastCompletions: Map<Long, Long>,
        completionDays: Map<Long, Set<LocalDate>>,
        lastSeen: Long,
        now: Long,
        zone: ZoneId,
        toleranceMinutes: Long = LATE_TOLERANCE_MINUTES,
    ): Plan {
        if (now <= lastSeen) return Plan.EMPTY
        val toleranceMs = toleranceMinutes * 60_000L
        val late = ArrayList<Occurrence>()
        var missed = 0
        for (task in tasks) {
            if (task.reminderAt == null) continue
            if (TaskSchedule.isFinished(task, lastCompletions[task.id], zone)) continue
            val doneDays = completionDays[task.id].orEmpty()
            val pending = occurrences(task, lastSeen, now, zone).filterNot { isDiscarded(task, it.firedAt, doneDays, zone) }
            val latest = pending.lastOrNull() ?: continue
            val latestIsLate = now - latest.firedAt <= toleranceMs
            if (latestIsLate) late += latest else missed++
            // As anteriores da mesma tarefa não saem como lembrete: contam se já passaram da tolerância.
            missed += pending.count { it != latest && now - it.firedAt > toleranceMs }
        }
        late.sortBy { it.firedAt }
        return Plan(late, missed)
    }

    /**
     * A ocorrência do lembrete de [task] em [firedAt] é descartada na entrega: tarefa repetida e o
     * dia dela (no [zone]) já tem conclusão ([doneDays]). Tarefa sem repetição nunca cai aqui (a
     * encerrada sai antes, por [TaskSchedule.isFinished]). O [plan] e o diagnóstico de alarmes
     * ([com.kalendrys.app.data.repository.TaskRepository.nextReminderToDeliver]) usam a mesma regra.
     */
    fun isDiscarded(task: Task, firedAt: Long, doneDays: Set<LocalDate>, zone: ZoneId): Boolean =
        task.repeat != ReminderRepeat.NONE && Instant.ofEpochMilli(firedAt).atZone(zone).toLocalDate() in doneDays

    /** Ocorrências do lembrete de [task] em `(lastSeen, now]`, em ordem de horário. */
    fun occurrences(task: Task, lastSeen: Long, now: Long, zone: ZoneId): List<Occurrence> {
        val reminderAt = task.reminderAt ?: return emptyList()
        if (now <= lastSeen) return emptyList()
        val anchor = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), zone)
        val fromDate = Instant.ofEpochMilli(lastSeen).atZone(zone).toLocalDate()
        val toDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ReminderRecurrence.occurrencesBetween(
            anchor,
            task.repeat,
            fromDate,
            toDate,
            every = task.intervalEvery,
            unit = task.intervalUnit,
            byWeekdays = task.byWeekdays,
        ).map { date -> Occurrence(task.id, date.atTime(anchor.toLocalTime()).atZone(zone).toInstant().toEpochMilli()) }
            .filter { it.firedAt > lastSeen && it.firedAt <= now }
    }
}
