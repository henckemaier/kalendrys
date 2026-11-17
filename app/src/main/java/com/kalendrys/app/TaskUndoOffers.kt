package com.kalendrys.app

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Ofertas de Desfazer de uma tarefa excluída pelo detalhe (v1.8.0, E0-D), criadas no [KalendrysApp]
 * no molde do [NewTaskDraftStore]. O detalhe exclui e oferece; o `KalendrysNavHost`, sempre
 * composto, é o único coletor e mostra a mensagem na tela de origem depois que o detalhe fecha.
 *
 * Só em memória: uma oferta não consumida some com o processo (sem persistência). O canal tem buffer,
 * para a oferta feita enquanto ninguém coleta (ex.: a Activity sendo recriada) chegar quando o
 * coletor voltar.
 *
 * Desfazer usa o mesmo caminho do deslize na lista: [TaskRepository.restoreTask] (mesmo id, mesmo
 * histórico, vínculo de lista e lembrete reagendado). Ele roda em [scope], do app, e não no coletor:
 * uma oferta nova cancela a espera da anterior (collectLatest) mas não pode cancelar um restauro já
 * pedido no meio.
 */
class TaskUndoOffers(
    private val repository: TaskRepository,
    private val scope: CoroutineScope,
) {
    private val channel = Channel<Task>(Channel.BUFFERED)

    /** Tarefas excluídas com Desfazer a oferecer, na ordem; cada uma é entregue uma vez. */
    val offers: Flow<Task> = channel.receiveAsFlow()

    /** [task] acabou de ser excluída (com os dados de antes da exclusão). */
    fun offer(task: Task) {
        channel.trySend(task)
    }

    /** A pessoa tocou em Desfazer: a tarefa volta como estava. */
    fun restore(task: Task): Job = scope.launch { repository.restoreTask(task) }
}
