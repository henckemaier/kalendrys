package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regra única de quando o "3/8" aparece (ADR-0002, 12.4): só com a lista tendo itens. Vale para o
 * `ProgressLine`, as linhas de tarefa e rotina, o cartão, o widget e as notificações.
 */
class ShoppingProgressVisibilityTest {

    @Test
    fun `lista com itens mostra o progresso, mesmo sem nada marcado ou com tudo marcado`() {
        assertTrue(ShoppingProgress(picked = 0, total = 1).isVisible)
        assertTrue(ShoppingProgress(picked = 3, total = 8).isVisible)
        assertTrue(ShoppingProgress(picked = 8, total = 8).isVisible)
    }

    @Test
    fun `lista vazia esconde o progresso e nunca esta completa`() {
        assertFalse(ShoppingProgress.EMPTY.isVisible)
        assertFalse(ShoppingProgress.EMPTY.isComplete)
        assertNull(ShoppingProgress.EMPTY.visibleOrNull())
    }

    @Test
    fun `visibleOrNull devolve o proprio progresso ou null`() {
        val progress = ShoppingProgress(picked = 3, total = 8)
        assertSame(progress, progress.visibleOrNull())
        assertNull((null as ShoppingProgress?).visibleOrNull())
    }

    @Test
    fun `visibleProgressFor esconde sem lista, vinculo orfao e lista vazia`() {
        val byList = mapOf(1L to ShoppingProgress(picked = 3, total = 8), 2L to ShoppingProgress.EMPTY)
        assertEquals(ShoppingProgress(picked = 3, total = 8), byList.visibleProgressFor(1L))
        assertNull(byList.visibleProgressFor(2L))
        assertNull(byList.visibleProgressFor(99L))
        assertNull(byList.visibleProgressFor(null))
    }

    @Test
    fun `titulo com progresso segue a mesma regra`() {
        assertEquals("Feira · 3/8", ShoppingProgress.titleWithProgress("Feira", ShoppingProgress(3, 8)))
        assertEquals("Feira", ShoppingProgress.titleWithProgress("Feira", ShoppingProgress.EMPTY))
        assertEquals("Feira", ShoppingProgress.titleWithProgress("Feira", null))
    }
}
