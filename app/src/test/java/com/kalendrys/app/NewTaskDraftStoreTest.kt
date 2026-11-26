package com.kalendrys.app

import com.kalendrys.app.domain.TaskTiming
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dono único do rascunho (v1.7.1): fechar guarda, Cancelar e Salvar descartam. */
class NewTaskDraftStoreTest {

    private val today = LocalDate.of(2026, 11, 16)
    private val tapped = LocalDate.of(2026, 11, 20)
    private val store = NewTaskDraftStore()

    @Test
    fun `sem rascunho, abrir cria um na data sugerida`() {
        assertNull(store.draft.value)
        store.open(today)
        assertEquals(today, store.draft.value!!.timing.date)
    }

    @Test
    fun `fechar e reabrir devolve o que foi digitado`() {
        store.open(today)
        store.update { it.copy(title = "Comprar pão", location = "Padaria") }
        // Fechar o painel não chama o store: o rascunho só fica lá.
        store.open(today)
        assertEquals("Comprar pão", store.draft.value!!.title)
        assertEquals("Padaria", store.draft.value!!.location)
    }

    @Test
    fun `aberto pelo calendario usa o dia tocado ate o usuario escolher a data`() {
        store.open(today)
        store.update { it.copy(title = "Dentista") }
        store.open(tapped)
        assertEquals(tapped, store.draft.value!!.timing.date)

        val chosen = LocalDate.of(2026, 12, 3)
        store.update { it.withTiming(it.timing.copy(date = chosen)) }
        store.open(today)
        assertEquals(chosen, store.draft.value!!.timing.date)
        assertEquals("Dentista", store.draft.value!!.title)
    }

    @Test
    fun `descartar devolve o rascunho e o proximo abre vazio`() {
        store.open(today)
        store.update { it.copy(title = "Ligar") }
        assertEquals("Ligar", store.discard()!!.title)
        assertNull(store.draft.value)
        assertNull(store.discard())

        store.open(today)
        assertEquals("", store.draft.value!!.title)
    }

    @Test
    fun `mudanca sem rascunho aberto e ignorada`() {
        store.update { it.copy(title = "fantasma") }
        assertNull(store.draft.value)
    }

}
