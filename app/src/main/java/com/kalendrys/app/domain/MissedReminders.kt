package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Quais ocorrências de lembrete deveriam ter disparado enquanto o app não estava de pé para
 * entregá-las (aparelho desligado, Doze, "forçar parada"), para recuperá-las depois numa única
 * notificação agregada.
 *
 * Trabalha sobre a âncora ([Task.reminderAt]) + a repetição de cada tarefa, reusando
 * [ReminderRecurrence] — não reimplementa recorrência. A janela é SEMI-ABERTA embaixo e fechada
 * em cima: `(lastSeen, now]`. O instante [lastSeen] já foi processado, então não é recontado; o
 * próximo disparo (estritamente depois de [now]) fica para o reagendamento normal, então não há
 * duplo-disparo. Lógica pura, testada na JVM.
 */
object MissedReminders {

    /** Uma ocorrência que deveria ter tocado entre o último processamento e agora, mas não tocou. */
    data class Occurrence(val taskId: Long, val firedAt: Long)

    /**
     * Ocorrências perdidas em `(lastSeen, now]`, em ordem de horário. Cada tarefa entra pela sua
     * âncora/repetição; tarefas sem lembrete ([Task.reminderAt] nulo) são ignoradas. Cabe ao
     * chamador não passar tarefas já encerradas (one-time concluída), que não devem gerar aviso.
     */
    fun between(tasks: List<Task>, lastSeen: Long, now: Long, zone: ZoneId): List<Occurrence> {
        if (now <= lastSeen) return emptyList()
        val fromDate = Instant.ofEpochMilli(lastSeen).atZone(zone).toLocalDate()
        val toDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val missed = ArrayList<Occurrence>()
        for (task in tasks) {
            val reminderAt = task.reminderAt ?: continue
            val anchor = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), zone)
            for (date in ReminderRecurrence.occurrencesBetween(anchor, task.repeat, fromDate, toDate)) {
                val firedAt = date.atTime(anchor.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                // Janela semiaberta: o instante de lastSeen já foi tratado; now ainda conta.
                if (firedAt > lastSeen && firedAt <= now) {
                    missed += Occurrence(task.id, firedAt)
                }
            }
        }
        missed.sortBy { it.firedAt }
        return missed
    }
}
