package com.kalendrys.app.ui.navigation

import com.kalendrys.app.ui.shopping.shoppingListRoute
import com.kalendrys.app.ui.taskdetail.taskDetailRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pilha do link de lista (v1.10.0, ADR-0002 16.10.4, troca a regra E-1): a lista abre sobre o detalhe
 * da tarefa DONA, e esse sobre a aba Tarefas (que a navegação abre antes). Lista sem dona: só a aba.
 */
class ListLinkStackTest {

    @Test
    fun `com dona - detalhe da dona e a lista por cima`() {
        assertEquals(listOf(taskDetailRoute(3), shoppingListRoute(7, 3)), listLinkStack(listId = 7, ownerTaskId = 3))
    }

    @Test
    fun `sem dona ou lista que nao existe mais - fica na aba Tarefas`() {
        assertTrue(listLinkStack(listId = 7, ownerTaskId = null).isEmpty())
    }
}
