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

/**
 * Uma oferta de Desfazer: [id] único por oferta, para limpar só a que foi mostrada; [createdAt] (epoch
 * millis) marca o início da validade ([TaskUndoOffers.VALIDITY_MS]).
 */
data class TaskUndoOffer(val id: Long, val task: Task, val createdAt: Long)

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
 * Validade (A1 da revisão do NB-4): a oferta vale [VALIDITY_MS] a partir da criação. Vencida, o coletor
 * a limpa sem mostrar: quem exclui e sai do app pelo voltar não encontra "Tarefa excluída / Desfazer"
 * horas depois, e girar sem parar não segura a mensagem para sempre. Girar dentro do prazo mostra de novo.
 *
 * Desfazer usa o mesmo caminho do deslize na lista: [TaskRepository.restoreTask] (mesmo id, mesmo
 * histórico, vínculo de lista e lembrete reagendado). Ele roda em [scope], do app, e não no coletor:
 * uma oferta nova ou a recriação da tela não podem cancelar um restauro já pedido no meio.
 */
class TaskUndoOffers(
    private val repository: TaskRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val nextId = AtomicLong()
    private val _pending = MutableStateFlow<TaskUndoOffer?>(null)

    /** A oferta a mostrar; `null` = nenhuma. */
    val pending: StateFlow<TaskUndoOffer?> = _pending.asStateFlow()

    /** [task] acabou de ser excluída (com os dados de antes da exclusão); substitui a pendente. */
    fun offer(task: Task) {
        _pending.value = TaskUndoOffer(nextId.incrementAndGet(), task, createdAt = clock())
    }

    /** [offer] passou do prazo de validade e não deve mais ser mostrada. */
    fun isExpired(offer: TaskUndoOffer): Boolean = remainingMs(offer) == 0L

    /** Quanto ainda resta do prazo de [offer], em ms (0 se já venceu): o limite da espera da mensagem. */
    fun remainingMs(offer: TaskUndoOffer): Long = (VALIDITY_MS - (clock() - offer.createdAt)).coerceAtLeast(0L)

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

    companion object {
        /** Prazo da oferta: curto, folgado para a mensagem longa (10 s) e para girar a tela no meio. */
        const val VALIDITY_MS = 30_000L
    }
}
