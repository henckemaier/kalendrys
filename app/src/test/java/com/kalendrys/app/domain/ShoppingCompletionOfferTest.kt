package com.kalendrys.app.domain

import com.kalendrys.app.domain.ShoppingCompletionOffer.LinkedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShoppingCompletionOfferTest {

    private val complete = ShoppingProgress(picked = 3, total = 3)

    @Test
    fun `tarefa de origem pendente recebe a oferta`() =
        assertEquals(2L, ShoppingCompletionOffer.candidate(complete, originTaskId = 2, linkedTasks = listOf(LinkedTask(1, true), LinkedTask(2, true))))

    @Test
    fun `sem origem a unica pendente recebe a oferta`() =
        assertEquals(1L, ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(1, true), LinkedTask(2, false))))

    @Test
    fun `nenhuma pendente nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(1, false))))

    @Test
    fun `mais de uma pendente sem origem nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(1, true), LinkedTask(2, true))))

    @Test
    fun `lista vazia nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(ShoppingProgress.EMPTY, originTaskId = 1, linkedTasks = listOf(LinkedTask(1, true))))

    @Test
    fun `lista com item desmarcado nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(ShoppingProgress(2, 3), originTaskId = 1, linkedTasks = listOf(LinkedTask(1, true))))

    @Test
    fun `tarefa de origem ja concluida cai na unica pendente`() {
        assertEquals(2L, ShoppingCompletionOffer.candidate(complete, originTaskId = 1, linkedTasks = listOf(LinkedTask(1, false), LinkedTask(2, true))))
        assertNull(ShoppingCompletionOffer.candidate(complete, originTaskId = 1, linkedTasks = listOf(LinkedTask(1, false))))
    }
}
