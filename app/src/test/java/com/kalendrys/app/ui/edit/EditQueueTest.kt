package com.kalendrys.app.ui.edit

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fila única das gravações de um editor (v1.9.0, ADR-0002 15.3): na ordem do toque mesmo no
 * Dispatchers.Default, com a linha mais recente, barreira, linha que não existe mais e fechamento.
 */
// Robolectric: a fila registra falhas pelo android.util.Log.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditQueueTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `executa na ordem do toque mesmo no Default`() = runBlocking {
        var row = 0
        val queue = EditQueue(scope) { row }
        val seen = Collections.synchronizedList(mutableListOf<Int>())

        repeat(200) { i ->
            queue.enqueue {
                // Uma ação lenta não deixa a seguinte passar na frente.
                if (i % 50 == 0) delay(5)
                seen += i
                row = i
            }
        }
        withTimeout(5_000) { queue.awaitIdle() }

        assertEquals((0 until 200).toList(), seen.toList())
    }

    @Test
    fun `cada acao recebe a linha lida na hora`() = runBlocking {
        var row = 1
        val queue = EditQueue(scope) { row }
        val received = Collections.synchronizedList(mutableListOf<Int>())

        queue.enqueue { current -> received += current; row = current * 10 }
        queue.enqueue { current -> received += current }
        withTimeout(5_000) { queue.awaitIdle() }

        assertEquals(listOf(1, 10), received.toList())
    }

    @Test
    fun `linha que nao existe mais - a acao nao roda e a fila segue`() = runBlocking {
        var exists = false
        val queue = EditQueue<Int>(scope) { if (exists) 7 else null }
        val ran = Collections.synchronizedList(mutableListOf<Int>())

        queue.enqueue { ran += 1 }
        withTimeout(5_000) { queue.awaitIdle() }
        exists = true
        queue.enqueue { ran += it }
        withTimeout(5_000) { queue.awaitIdle() }

        assertEquals(listOf(7), ran.toList())
    }

    @Test
    fun `acao que falha nao para a fila`() = runBlocking {
        val queue = EditQueue(scope) { 0 }
        val ran = Collections.synchronizedList(mutableListOf<String>())

        queue.enqueue { error("falhou") }
        queue.enqueue { ran += "depois" }
        withTimeout(5_000) { queue.awaitIdle() }

        assertEquals(listOf("depois"), ran.toList())
    }

    @Test
    fun `fechar termina o que ja estava e recusa o resto`() = runBlocking {
        val queue = EditQueue(scope) { 0 }
        val ran = Collections.synchronizedList(mutableListOf<String>())

        val before = queue.enqueue { delay(20); ran += "antes" }
        queue.close()
        // Com a fila fechada, o trabalho devolvido é o do consumidor: esperar por ele espera a fila
        // esvaziar (B1 da revisão da E2), em vez de passar na hora com "antes" ainda pendente.
        val after = queue.enqueue { ran += "depois" }
        withTimeout(5_000) { after.join() }

        assertTrue(before.isCompleted)
        assertEquals(listOf("antes"), ran.toList())
        withTimeout(5_000) { queue.awaitIdle() } // fechada e vazia: passa
    }
}
