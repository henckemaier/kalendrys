package com.kalendrys.app

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Uma oferta de Desfazer: [id] único por oferta, para limpar só a que foi mostrada. */
data class TaskUndoOffer(val id: Long, val task: Task)

/**
 * Oferta de Desfazer de uma tarefa excluída pelo detalhe (v1.8.0, E0-D), criada no [KalendrysApp] no
 * molde do [NewTaskDraftStore]. O detalhe exclui e oferece; o `KalendrysNavHost`, sempre composto, é o
 * único coletor e mostra a mensagem na tela de origem depois que o detalhe fecha.
 *
 * É ESTADO, não evento (NB-4): fica UMA oferta pendente ([pending]) até a mensagem terminar de verdade
 * (Desfazer tocado, tempo esgotado ou dispensada). Recriar a tela (girar) recompõe o coletor, que
 * mostra a pendente de novo, com o tempo reiniciado. Oferta nova substitui a anterior, que fica
 * excluída. Só em memória: uma oferta pendente some com o processo (sem persistência).
 *
 * Desfazer usa o mesmo caminho do deslize na lista: [TaskRepository.restoreTask] (mesmo id, mesmo
 * histórico, vínculo de lista e lembrete reagendado). Ele roda em [scope], do app, e não no coletor:
 * uma oferta nova ou a recriação da tela não podem cancelar um restauro já pedido no meio.
 */
class TaskUndoOffers(
    private val repository: TaskRepository,
    private val scope: CoroutineScope,
) {
    private val nextId = AtomicLong()
    private val _pending = MutableStateFlow<TaskUndoOffer?>(null)

    /** A oferta a mostrar; `null` = nenhuma. */
    val pending: StateFlow<TaskUndoOffer?> = _pending.asStateFlow()

    /** [task] acabou de ser excluída (com os dados de antes da exclusão); substitui a pendente. */
    fun offer(task: Task) {
        _pending.value = TaskUndoOffer(nextId.incrementAndGet(), task)
    }

    /**
     * A mensagem de [offer] terminou sem Desfazer (tempo esgotado ou dispensada): a tarefa fica
     * excluída. Só limpa se [offer] ainda for a pendente; uma nova que chegou no meio fica.
     */
    fun finish(offer: TaskUndoOffer) {
        _pending.compareAndSet(offer, null)
    }

    /** A pessoa tocou em Desfazer na mensagem de [offer]: limpa a oferta e a tarefa volta como estava. */
    fun undo(offer: TaskUndoOffer): Job {
        // O restauro é pedido antes de limpar: limpar acorda o coletor, e o restauro (no escopo do app)
        // já está a caminho, sem depender do que o coletor fizer depois.
        val restore = scope.launch { repository.restoreTask(offer.task) }
        finish(offer)
        return restore
    }
}
