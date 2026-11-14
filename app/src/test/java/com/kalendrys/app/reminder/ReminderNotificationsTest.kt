package com.kalendrys.app.reminder

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.reminder.ReminderNotifications.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ações do lembrete de tarefa (ADR-0002, E-1): "Abrir lista" só quando a tarefa tem lista válida. */
class ReminderNotificationsTest {

    private fun task(shoppingListId: Long?) = Task(id = 1, title = "Feira", shoppingListId = shoppingListId)

    @Test
    fun `tarefa sem lista mantem Concluir e Adiar`() {
        assertEquals(listOf(Action.COMPLETE, Action.SNOOZE), ReminderNotifications.actionsFor(task(null), null))
    }

    @Test
    fun `tarefa com lista ganha Abrir lista por ultimo`() {
        assertEquals(
            listOf(Action.COMPLETE, Action.SNOOZE, Action.OPEN_LIST),
            ReminderNotifications.actionsFor(task(7), ShoppingProgress(picked = 3, total = 8)),
        )
    }

    @Test
    fun `lista vazia tambem ganha Abrir lista`() {
        assertEquals(
            listOf(Action.COMPLETE, Action.SNOOZE, Action.OPEN_LIST),
            ReminderNotifications.actionsFor(task(7), ShoppingProgress.EMPTY),
        )
    }

    @Test
    fun `vinculo orfao fica como tarefa sem lista`() {
        // Lista excluída: o progresso não existe (null), então nada de "Abrir lista".
        assertEquals(listOf(Action.COMPLETE, Action.SNOOZE), ReminderNotifications.actionsFor(task(7), null))
    }

    @Test
    fun `titulo do lembrete usa o formato unico com progresso`() {
        assertEquals("Feira · 3/8", ShoppingProgress.titleWithProgress("Feira", ShoppingProgress(3, 8)))
        assertEquals("Feira", ShoppingProgress.titleWithProgress("Feira", null))
    }
}
