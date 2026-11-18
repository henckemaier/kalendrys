package com.kalendrys.app

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** O que uma oferta de Desfazer devolve: um tipo fechado por exclusão que nasce numa tela e aparece em outra. */
sealed interface UndoTarget {
    /** Tarefa excluída pelo detalhe (v1.8.0, E0-D), com os dados de antes da exclusão. */
    data class DeletedTask(val task: Task) : UndoTarget

    /** Rotina excluída (arquivada) pelo editor (v1.8.0, P8). */
    data class DeletedRoutine(val routineId: Long) : UndoTarget
}

/**
 * Uma oferta de Desfazer: [id] único por oferta, para limpar só a que foi mostrada; [createdAt] (epoch
 * millis) marca o início da validade ([UndoOffers.VALIDITY_MS]).
 */
data class UndoOffer(val id: Long, val target: UndoTarget, val createdAt: Long)

/**
 * Oferta de Desfazer das exclusões que fecham a tela (tarefa pelo detalhe, E0-D; rotina pelo editor,
 * P8), criada no [KalendrysApp] no molde do [NewTaskDraftStore]. A tela exclui e oferece; o
 * `KalendrysNavHost`, sempre composto, é o único coletor e mostra a mensagem na tela de origem depois
 * que a tela que excluiu fecha.
 *
 * É ESTADO, não evento (NB-4): fica UMA oferta pendente ([pending]), de qualquer tipo, até a mensagem
 * terminar de verdade (Desfazer tocado, tempo esgotado ou dispensada). Recriar a tela (girar) recompõe
 * o coletor, que mostra a pendente de novo, com o tempo reiniciado. Oferta nova substitui a anterior,
 * de qualquer tipo, que fica excluída. Só em memória: uma oferta pendente some com o processo.
 *
 * Validade (A1 da revisão do NB-4): a oferta vale [VALIDITY_MS] a partir da criação. Vencida, o coletor
 * a limpa sem mostrar: quem exclui e sai do app pelo voltar não encontra a mensagem com Desfazer horas
 * depois, e girar sem parar não segura a mensagem para sempre. Girar dentro do prazo mostra de novo.
 *
 * Quem restaura é ESTE objeto, não o coletor, no [scope] do app: uma oferta nova ou a recriação da tela
 * não podem cancelar um restauro já pedido no meio. Tarefa: [TaskRepository.restoreTask] (o mesmo do
 * deslize na lista: mesmo id, histórico, vínculo de lista e lembrete reagendado). Rotina:
 * [RoutineRepository.unarchive] (pelo mesmo change{} que reagenda os alarmes; volta como está gravada).
 */
class UndoOffers(
    private val tasks: TaskRepository,
    private val routines: RoutineRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val nextId = AtomicLong()
    private val _pending = MutableStateFlow<UndoOffer?>(null)

    /** A oferta a mostrar; `null` = nenhuma. */
    val pending: StateFlow<UndoOffer?> = _pending.asStateFlow()

    /** [target] acabou de ser excluído; substitui a pendente, de qualquer tipo. */
    fun offer(target: UndoTarget) {
        _pending.value = UndoOffer(nextId.incrementAndGet(), target, createdAt = clock())
    }

    /**
     * EXCLUIR E OFERECER a tarefa numa operação só, no [scope] do app (NB-E3-C): a tela só pede e
     * fecha. Fechar a tela (cancelar o escopo dela) logo depois de pedir não perde nem a exclusão
     * nem a oferta. [task] vai como estava no banco (lembrete e vínculo de lista), para o Desfazer.
     */
    fun deleteTask(task: Task): Job = scope.launch {
        tasks.deleteTask(task)
        offer(UndoTarget.DeletedTask(task))
    }

    /**
     * EXCLUIR (arquivar) E OFERECER a rotina numa operação só, no [scope] do app (NB-E3-C): o editor e
     * Todas as rotinas só pedem. O Desfazer devolve a rotina como está gravada.
     */
    fun deleteRoutine(routine: Routine): Job = scope.launch {
        routines.archive(routine)
        offer(UndoTarget.DeletedRoutine(routine.id))
    }

    /** [offer] passou do prazo de validade e não deve mais ser mostrada. */
    fun isExpired(offer: UndoOffer): Boolean = remainingMs(offer) == 0L

    /** Quanto ainda resta do prazo de [offer], em ms (0 se já venceu): o limite da espera da mensagem. */
    fun remainingMs(offer: UndoOffer): Long = (VALIDITY_MS - (clock() - offer.createdAt)).coerceAtLeast(0L)

    /**
     * A mensagem de [offer] terminou sem Desfazer (tempo esgotado ou dispensada): fica excluído. Só
     * limpa se [offer] ainda for a pendente; uma nova que chegou no meio fica.
     */
    fun finish(offer: UndoOffer) {
        _pending.compareAndSet(offer, null)
    }

    /** A pessoa tocou em Desfazer na mensagem de [offer]: limpa a oferta e o excluído volta. */
    fun undo(offer: UndoOffer): Job {
        // O restauro é pedido antes de limpar: limpar acorda o coletor, e o restauro (no escopo do app)
        // já está a caminho, sem depender do que o coletor fizer depois.
        val restore = scope.launch {
            when (val target = offer.target) {
                is UndoTarget.DeletedTask -> tasks.restoreTask(target.task)
                is UndoTarget.DeletedRoutine -> routines.unarchive(target.routineId)
            }
        }
        finish(offer)
        return restore
    }

    companion object {
        /** Prazo da oferta: curto, folgado para a mensagem longa (10 s) e para girar a tela no meio. */
        const val VALIDITY_MS = 30_000L
    }
}
