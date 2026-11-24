package com.kalendrys.app.ui.edit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buffer de campo de texto do salvar na hora (v1.9.0, ADR-0002 15.3): espera de 500 ms, gravação no
 * flush, banco que só entra sem digitação pendente, valor recusado e o eco da própria gravação (A1 da
 * revisão da E3). Tudo em tempo virtual; a "gravação" é uma lista e um banco em memória.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextFieldBufferTest {

    private val written = mutableListOf<String>()

    /** Banco de mentira: grava aparado, como o edit do repositório; o teste devolve o eco pelo onStored. */
    private var stored = ""

    private fun TestScope.buffer(isValid: (String) -> Boolean = { true }): TextFieldBuffer {
        val buffer = TextFieldBuffer(timerScope = backgroundScope, writeScope = backgroundScope, isValid = isValid) { value ->
            backgroundScope.launch {
                written += value
                stored = value.trim()
            }
        }
        buffer.onStored("Antes")
        stored = "Antes"
        return buffer
    }

    @Test
    fun `grava 500 ms depois da ultima tecla, uma vez`() = runTest {
        val b = buffer()
        b.onType("A")
        advanceTimeBy(300)
        b.onType("Ab")
        advanceTimeBy(499)
        runCurrent()
        assertTrue(written.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf("Ab"), written)
        assertFalse(b.hasPending)
    }

    @Test
    fun `flush grava na hora`() = runTest {
        val b = buffer()
        b.onType("Agora")

        b.flush()
        runCurrent()

        assertEquals(listOf("Agora"), written)
    }

    @Test
    fun `eco aparado nao apaga o espaco do fim`() = runTest {
        val b = buffer()
        b.onType("Comprar ")
        advanceTimeBy(501)
        runCurrent()
        // O eco chega DEPOIS de a gravação terminar (pendência já limpa): é o caso do A1.
        assertFalse(b.hasPending)
        b.onStored(stored)

        assertEquals("Comprar", stored)
        assertEquals("Comprar ", b.text.value)
    }

    @Test
    fun `eco aparado nao apaga a quebra de linha do fim`() = runTest {
        val b = buffer()
        b.onType("linha 1\n")
        advanceTimeBy(501)
        runCurrent()
        b.onStored(stored)

        assertEquals("linha 1\n", b.text.value)
    }

    @Test
    fun `mudanca de fora entra quando nao ha digitacao pendente`() = runTest {
        val b = buffer()

        b.onStored("De fora")

        assertEquals("De fora", b.text.value)
    }

    @Test
    fun `mudanca de fora nao entra com digitacao pendente`() = runTest {
        val b = buffer()
        b.onType("digitando")

        b.onStored("De fora")

        assertEquals("digitando", b.text.value)
    }

    @Test
    fun `valor recusado nao grava e continua no campo`() = runTest {
        val b = buffer(isValid = { it.isNotBlank() })
        b.onType("")
        advanceTimeBy(501)
        b.flush()
        runCurrent()

        assertTrue(written.isEmpty())
        assertEquals("", b.text.value)
        assertTrue(b.hasPending)
        // O banco não sobrescreve enquanto a recusa está pendente.
        b.onStored("Antes")
        assertEquals("", b.text.value)
    }

    @Test
    fun `reset esquece a digitacao e mostra o valor`() = runTest {
        val b = buffer()
        b.onType("mexido")

        b.reset("Antes")
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(written.isEmpty())
        assertEquals("Antes", b.text.value)
        assertFalse(b.hasPending)
    }

    @Test
    fun `tecla nova durante a gravacao continua pendente`() = runTest {
        val gate = Job()
        val b = TextFieldBuffer(timerScope = backgroundScope, writeScope = backgroundScope) { value ->
            backgroundScope.launch {
                gate.join()
                written += value
            }
        }
        b.onStored("")
        b.onType("um")
        b.flush()
        b.onType("um dois")
        gate.complete()
        runCurrent()

        assertTrue(b.hasPending)
        assertEquals("um dois", b.text.value)
    }

    /**
     * E3-A da Coruja: rajada de teclas SEM avançar o despachador. O texto do campo ([fieldText]) já é
     * o digitado depois de cada tecla, inteiro e em ordem, sem esperar fluxo nenhum; e o que se grava
     * no fim é esse texto.
     */
    @Test
    fun `rajada sem avancar o despachador - o campo ja tem cada tecla, em ordem, e grava o texto inteiro`() = runTest {
        val b = buffer()
        val frase = "Mercado do bairro com a lista completa da semana"
        b.onType("")

        frase.forEachIndexed { i, _ ->
            val digitado = frase.substring(0, i + 1)
            b.onType(digitado)
            assertEquals("tecla ${i + 1}", digitado, b.fieldText)
        }
        assertTrue("nada gravado no meio da rajada", written.isEmpty())

        advanceTimeBy(501)
        runCurrent()

        assertEquals(listOf(frase), written)
        assertEquals(frase, b.fieldText)
    }

    @Test
    fun `o campo e o fluxo andam juntos e o eco nao troca o campo`() = runTest {
        val b = buffer()
        assertEquals("Antes", b.fieldText)

        b.onType("Comprar ")
        advanceTimeBy(501)
        runCurrent()
        b.onStored(stored)

        assertEquals("Comprar ", b.fieldText)
        assertEquals(b.fieldText, b.text.value)
    }
}
