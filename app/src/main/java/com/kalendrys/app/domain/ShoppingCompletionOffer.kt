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
 * "Pendente" ([isPending]): atrasada, de hoje ou sem data, ainda não concluída, e TAMBÉM a tarefa
 * ÚNICA com data futura (decisão do usuário, NB-2 da v1.6.0: a compra pode ser feita antes do dia).
 * Tarefa repetida já concluída hoje, ou cuja próxima ocorrência é futura, fica de fora. Rotinas não
 * recebem oferta.
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

    /** A tarefa conta como pendente para a oferta? Ver a regra no KDoc do objeto. */
    fun isPending(status: TaskSchedule.Status, repeat: ReminderRepeat): Boolean = when (status) {
        TaskSchedule.Status.OVERDUE, TaskSchedule.Status.TODAY, TaskSchedule.Status.NO_DATE -> true
        TaskSchedule.Status.UPCOMING -> repeat == ReminderRepeat.NONE
        else -> false
    }
}
