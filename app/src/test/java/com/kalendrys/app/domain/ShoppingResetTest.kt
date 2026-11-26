package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingResetTest {

    @Test
    fun `tarefa unica nao reinicia`() = assertFalse(ShoppingReset.taskResets(ReminderRepeat.NONE))

    @Test
    fun `tarefa repetida reinicia`() {
        ReminderRepeat.entries.filter { it != ReminderRepeat.NONE }.forEach {
            assertTrue("$it deveria reiniciar", ShoppingReset.taskResets(it))
        }
    }

    // v1.10.0: rotina não tem lista, então não há reinício de lista por bloco (saiu routineResets/routineKey).

    @Test
    fun `chave do reinicio e a da conclusao da tarefa`() {
        assertEquals("task-log:42", ShoppingReset.taskKey(42))
    }

    @Test
    fun `progresso completo so com lista nao vazia toda marcada`() {
        assertFalse(ShoppingProgress.EMPTY.isComplete)
        assertFalse(ShoppingProgress(picked = 2, total = 3).isComplete)
        assertTrue(ShoppingProgress(picked = 3, total = 3).isComplete)
    }

    @Test
    fun `titulo com progresso so quando ha lista nao vazia`() {
        assertEquals("Feira · 3/8", ShoppingProgress.titleWithProgress("Feira", ShoppingProgress(3, 8)))
        assertEquals("Feira", ShoppingProgress.titleWithProgress("Feira", null))
        assertEquals("Feira", ShoppingProgress.titleWithProgress("Feira", ShoppingProgress.EMPTY))
    }
}
