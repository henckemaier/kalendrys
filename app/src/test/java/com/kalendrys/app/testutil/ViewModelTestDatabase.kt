package com.kalendrys.app.testutil

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.kalendrys.app.data.local.AppDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Banco em memória para testes de ViewModel, com encerramento ordenado.
 *
 * Por quê: os ViewModels observam o Room via `stateIn(viewModelScope, WhileSubscribed)`, e as
 * consultas rodam nos executores do Room, em outras threads. Fechar o banco (ou trocar o
 * Dispatchers.Main) com uma consulta ainda em voo dava falhas intermitentes: "no current
 * transaction" e "Dispatchers.Main is used concurrently with setting it".
 *
 * Aqui o Room usa um executor próprio de uma thread. No [close]: cancela o `viewModelScope` dos
 * ViewModels registrados em [track], espera o executor esvaziar e só então fecha o banco. Chame
 * [close] ANTES do `Dispatchers.resetMain()`.
 *
 * Limitações conhecidas:
 * - O MESMO executor de uma thread atende consultas e transações. Funciona porque, dentro de uma
 *   transação, o Room roda as chamadas de DAO na própria thread da transação. Mas um teste que,
 *   dentro de `withTransaction`, BLOQUEIE esperando o resultado de uma consulta agendada no executor
 *   (ex.: `flow.first()` fora do contexto da transação, ou `Future.get()`) trava: a única thread está
 *   ocupada com a transação. Se isso for preciso, use executores separados.
 * - As barreiras do [close] são heurísticas: esvaziam o que estava na fila e o que essas tarefas
 *   enfileiraram, o que basta para os testes atuais. Uma cadeia mais longa de tarefas que reagendam
 *   outras (ou trabalho em outro executor) não é garantida pelas duas barreiras.
 */
class ViewModelTestDatabase(context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val viewModels = mutableListOf<ViewModel>()

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(executor)
        .setTransactionExecutor(executor)
        .build()

    private val appScopes = mutableListOf<Job>()

    /**
     * Dono dos ViewModels registrados: o [close] chama [ViewModelStore.clear], que cancela o
     * viewModelScope E chama o onCleared, como ao fechar a tela (v1.9.0: os editores gravam o texto
     * pendente e fecham a fila de gravações no onCleared, B2 da revisão da E2).
     */
    private val store = ViewModelStore()

    /** Registra o ViewModel para ser encerrado (escopo cancelado e onCleared) no [close]. */
    fun <T : ViewModel> track(viewModel: T): T = viewModel.also {
        viewModels += it
        @Suppress("RestrictedApi")
        store.put("tracked-${viewModels.size}", it)
    }

    /**
     * Escopo "do app" para quem roda trabalho fora das telas sobre este banco (ex.: o restauro do
     * [com.kalendrys.app.UndoOffers]). No [close], o trabalho em voo termina (ou é cancelado e
     * esperado, inclusive trechos NonCancellable do repositório) ANTES de o banco fechar: sem isso,
     * um restauro atrasado de um teste batia no banco já fechado e estourava no teste seguinte
     * ("connection pool has been closed", UncaughtExceptionsBeforeTest).
     */
    fun appScope(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineScope {
        val job = SupervisorJob()
        appScopes += job
        return CoroutineScope(job + dispatcher)
    }

    fun close() {
        // Primeiro encerra os ViewModels (como sair da tela): o onCleared dos editores grava o pendente
        // e fecha a fila, e o consumidor dela no escopo do app termina ao esvaziar. Depois esvazia
        // o escopo do app com o banco ainda aberto.
        store.clear()
        runBlocking {
            appScopes.forEach { job ->
                // Deixa o restauro já pedido terminar com o banco aberto; preso, cancela e espera.
                withTimeoutOrNull(DRAIN_TIMEOUT_S * 1_000) { job.children.toList().joinAll() }
                // Com limite: nunca trava o fim do teste (o trabalho não depende do Main, que está preso aqui).
                withTimeoutOrNull(DRAIN_TIMEOUT_S * 1_000) { job.cancelAndJoin() }
            }
        }
        // Duas barreiras: o que já estava na fila e o que essas tarefas tenham enfileirado.
        repeat(2) { executor.submit {}.get(DRAIN_TIMEOUT_S, TimeUnit.SECONDS) }
        db.close()
        executor.shutdownNow()
    }

    private companion object {
        const val DRAIN_TIMEOUT_S = 5L
    }
}
