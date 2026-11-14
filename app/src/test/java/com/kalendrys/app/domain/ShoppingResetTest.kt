package com.kalendrys.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingResetTest {

    private val today = LocalDate.of(2026, 11, 13)

    @Test
    fun `tarefa unica nao reinicia`() = assertFalse(ShoppingReset.taskResets(ReminderRepeat.NONE))

    @Test
    fun `tarefa repetida reinicia`() {
        ReminderRepeat.entries.filter { it != ReminderRepeat.NONE }.forEach {
            assertTrue("$it deveria reiniciar", ShoppingReset.taskResets(it))
        }
    }

    @Test
    fun `bloco de hoje reinicia`() = assertTrue(ShoppingReset.routineResets(today, today))

    @Test
    fun `bloco de dia passado nao reinicia`() = assertFalse(ShoppingReset.routineResets(today.minusDays(1), today))

    @Test
    fun `chaves distinguem tarefa, rotina e dia`() {
        assertEquals("task-log:42", ShoppingReset.taskKey(42))
        assertEquals("routine:7:20000", ShoppingReset.routineKey(7, 20_000))
    }

    @Test
    fun `progresso completo so com lista nao vazia toda marcada`() {
        assertFalse(ShoppingProgress.EMPTY.isComplete)
        assertFalse(ShoppingProgress(picked = 2, total = 3).isComplete)
        assertTrue(ShoppingProgress(picked = 3, total = 3).isComplete)
    }
}
