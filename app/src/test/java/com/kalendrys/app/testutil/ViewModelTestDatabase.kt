package com.kalendrys.app.testutil

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.kalendrys.app.data.local.AppDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel

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
 */
class ViewModelTestDatabase(context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val viewModels = mutableListOf<ViewModel>()

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(executor)
        .setTransactionExecutor(executor)
        .build()

    /** Registra o ViewModel para ter o escopo cancelado no [close]. */
    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }

    fun close() {
        viewModels.forEach { it.viewModelScope.cancel() }
        // Duas barreiras: o que já estava na fila e o que essas tarefas tenham enfileirado.
        repeat(2) { executor.submit {}.get(DRAIN_TIMEOUT_S, TimeUnit.SECONDS) }
        db.close()
        executor.shutdownNow()
    }

    private companion object {
        const val DRAIN_TIMEOUT_S = 5L
    }
}
