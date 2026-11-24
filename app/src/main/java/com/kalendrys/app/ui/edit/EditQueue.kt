package com.kalendrys.app.ui.edit

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Fila ÚNICA de gravações de um editor do salvar na hora (v1.9.0, ADR-0002 15.3). Peça comum do
 * detalhe da tarefa (E2) e do editor de rotina (E3); uma por ViewModel.
 *
 * Por que existe: as gravações saem no escopo do app ([com.kalendrys.app.KalendrysApp.editScope]),
 * que roda no Dispatchers.Default, com várias threads. Dois `launch` seguidos ali NÃO têm ordem
 * garantida: o banco podia ficar com o texto mais velho, duas mudanças de data podiam inverter e o
 * Reverter podia ser atropelado por uma gravação disparada antes dele. A fila roda UMA ação por vez,
 * na ordem do toque.
 *
 * Cada ação recebe a linha MAIS RECENTE da entidade ([read]), lida na hora de gravar, para calcular a
 * mudança sobre ela (dois toques seguidos em dias não se anulam). Entidade que não existe mais: a ação
 * não roda. Ação que falha é registrada e as seguintes continuam.
 *
 * [enqueue] é síncrono e devolve o trabalho da ação (para esperar). [close] fecha a fila: as ações já
 * enfileiradas terminam e o consumidor acaba; o que chegar depois não roda, e o trabalho devolvido
 * nesse caso é o do CONSUMIDOR: quem espera (inclusive o [awaitIdle]) espera a fila esvaziar, em vez
 * de passar na hora com gravações ainda pendentes (B1 da revisão da E2).
 */
class EditQueue<T : Any>(
    writeScope: CoroutineScope,
    /** Lê a linha mais recente (o `observeX(id).first()` do repositório); null = não existe mais. */
    private val read: suspend () -> T?,
) {
    private class Item<T>(val action: suspend (T) -> Unit, val done: CompletableJob)

    private val items = Channel<Item<T>>(Channel.UNLIMITED)

    /** O consumidor: termina quando a fila, fechada, esvazia. */
    private val consumer: Job = writeScope.launch {
        for (item in items) {
            try {
                read()?.let { item.action(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Gravação do editor falhou; as seguintes continuam", error)
            } finally {
                item.done.complete()
            }
        }
    }

    /**
     * Enfileira [action] sobre a linha mais recente; devolve o trabalho dela. Com a fila já fechada a
     * ação não roda e o trabalho devolvido é o do consumidor (termina quando a fila esvaziar).
     */
    fun enqueue(action: suspend (T) -> Unit): Job {
        val done = Job()
        return if (items.trySend(Item(action, done)).isSuccess) done else consumer
    }

    /** Barreira: espera tudo o que já foi enfileirado terminar (com a fila fechada, que ela esvazie). */
    suspend fun awaitIdle() = enqueue {}.join()

    /** Fecha a fila: o que já está nela termina; depois disso nada mais roda. */
    fun close() {
        items.close()
    }

    private companion object {
        const val TAG = "EditQueue"
    }
}
