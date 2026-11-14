package com.kalendrys.app.domain

/**
 * Regra P3 do ADR-0001: ao marcar o último item de uma lista, qual tarefa recebe a oferta
 * "Concluir tarefa" (snackbar, sem forçar). A UI só chama isto quando uma marcação feita na tela
 * da lista deixou todos os itens marcados.
 *
 * - Lista vazia ou com item desmarcado: sem oferta.
 * - Tarefa de origem (a tela foi aberta pelo detalhe dela, `taskId` da rota), se ela está
 *   vinculada à lista e pendente.
 * - Senão, a ÚNICA tarefa pendente vinculada à lista. Zero ou mais de uma: sem oferta.
 *
 * "Pendente" é o mesmo critério do retrato do dia: atrasada, de hoje ou sem data, ainda não
 * concluída (ver [TaskSchedule.Status]). Rotinas não recebem oferta.
 */
object ShoppingCompletionOffer {

    /** Tarefa vinculada à lista e se ela está pendente agora. */
    data class LinkedTask(val taskId: Long, val pending: Boolean)

    fun candidate(progress: ShoppingProgress, originTaskId: Long?, linkedTasks: List<LinkedTask>): Long? {
        if (!progress.isComplete) return null
        val pending = linkedTasks.filter { it.pending }.map { it.taskId }
        if (originTaskId != null && originTaskId in pending) return originTaskId
        return pending.singleOrNull()
    }

    /** Estados que contam como pendente para a oferta (os mesmos das pendentes de hoje). */
    val PENDING_STATUSES: Set<TaskSchedule.Status> =
        setOf(TaskSchedule.Status.OVERDUE, TaskSchedule.Status.TODAY, TaskSchedule.Status.NO_DATE)
}
