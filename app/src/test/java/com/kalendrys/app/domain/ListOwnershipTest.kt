package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.domain.ListOwnership.TaskLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras de dados da v1.10.0 (ADR-0002 16.10.3, passos 1 e 4 a 6), puras. */
class ListOwnershipTest {

    private fun list(id: Long, name: String = "L$id", createdAt: Long = 10L, resetKey: String? = null) =
        ShoppingList(id = id, name = name, createdAt = createdAt, lastResetKey = resetKey)

    private fun item(id: Long, listId: Long, position: Int = 0, picked: Boolean = false, before: Boolean = false) =
        ShoppingItem(
            id = id, listId = listId, name = "i$id", quantity = 2, priceCents = 150, picked = picked,
            position = position, createdAt = 5L, pickedBeforeReset = before,
        )

    // --- Passo 1 ---------------------------------------------------------------------------------

    @Test
    fun `tipo pela categoria - so Exercicio vira Exercicio`() {
        assertEquals(RoutineKind.EXERCISE, ListOwnership.kindFromCategory("EXERCICIO"))
        assertEquals(RoutineKind.NONE, ListOwnership.kindFromCategory("SIMPLE"))
        assertEquals(RoutineKind.NONE, ListOwnership.kindFromCategory("COMPRAS"))
        assertEquals(RoutineKind.NONE, ListOwnership.kindFromCategory("ALGO_NOVO"))
        assertEquals("sem categoria (ou categoria que não existe)", RoutineKind.NONE, ListOwnership.kindFromCategory(null))
    }

    // --- Passos 4 a 6 ----------------------------------------------------------------------------

    @Test
    fun `dado ja correto - nada muda`() {
        val plan = ListOwnership.plan(
            tasks = listOf(TaskLink(1, 0, 10), TaskLink(2, 0, null), TaskLink(3, 0, 11)),
            lists = listOf(list(10), list(11)),
            items = listOf(item(100, 10), item(101, 11)),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `lista de duas tarefas - a mais antiga fica e a outra recebe copia com os itens`() {
        val plan = ListOwnership.plan(
            tasks = listOf(TaskLink(1, createdAt = 50, listId = 10), TaskLink(2, createdAt = 20, listId = 10)),
            lists = listOf(list(10, name = "Feira", resetKey = "task-log:9")),
            items = listOf(
                item(100, 10, position = 1, picked = true, before = true),
                item(101, 10, position = 0),
                item(200, 99), // item sem lista
            ),
        )
        // A tarefa 2 é a mais antiga (createdAt 20): fica com a 10; a 1 vai para a cópia 11.
        assertEquals(mapOf(1L to 11L), plan.taskLinks)
        assertEquals(listOf(ShoppingList(id = 11, name = "Feira", createdAt = 10, lastResetKey = null)), plan.newLists)
        // Ids novos acima do maior existente (200), na ordem da lista (posição, id); sem reinício pendente.
        // A cópia leva o conteúdo do item original (nome "i101" e "i100"), não o do id novo.
        assertEquals(
            listOf(
                item(101, 10, position = 0).copy(id = 201, listId = 11),
                item(100, 10, position = 1, picked = true, before = false).copy(id = 202, listId = 11),
            ),
            plan.newItems,
        )
        assertEquals(emptySet<Long>(), plan.deletedListIds)
        assertEquals(setOf(200L), plan.deletedItemIds)
    }

    @Test
    fun `lista de tres tarefas - duas copias e empate de criacao decidido pelo id`() {
        val plan = ListOwnership.plan(
            tasks = listOf(TaskLink(7, 30, 10), TaskLink(5, 30, 10), TaskLink(6, 40, 10), TaskLink(8, 0, 12)),
            lists = listOf(list(10), list(12)),
            items = listOf(item(1, 10)),
        )
        // 5 e 7 empatam em 30: fica a de menor id (5). Cópias para 7 e depois 6 (mais antiga antes).
        assertEquals(mapOf(7L to 13L, 6L to 14L), plan.taskLinks)
        assertEquals(listOf(13L, 14L), plan.newLists.map { it.id })
        assertEquals(listOf(2L to 13L, 3L to 14L), plan.newItems.map { it.id to it.listId })
        assertTrue(plan.deletedListIds.isEmpty())
    }

    @Test
    fun `vinculo de tarefa para lista que nao existe e limpo`() {
        val plan = ListOwnership.plan(
            tasks = listOf(TaskLink(1, 0, 404), TaskLink(2, 0, 10)),
            lists = listOf(list(10)),
            items = emptyList(),
        )
        assertEquals(mapOf(1L to null), plan.taskLinks)
        assertTrue(plan.newLists.isEmpty())
        assertTrue(plan.deletedListIds.isEmpty())
    }

    @Test
    fun `lista sem tarefa - avulsa ou so de rotina - sai com os itens, e item sem lista tambem`() {
        val plan = ListOwnership.plan(
            tasks = listOf(TaskLink(1, 0, 10)),
            // 11: avulsa; 12: era só de rotina (rotina não conta como dona).
            lists = listOf(list(10), list(11), list(12)),
            items = listOf(item(1, 10), item(2, 11), item(3, 12), item(4, 12), item(5, 77)),
        )
        assertTrue(plan.taskLinks.isEmpty())
        assertEquals(setOf(11L, 12L), plan.deletedListIds)
        assertEquals(setOf(2L, 3L, 4L, 5L), plan.deletedItemIds)
    }

    @Test
    fun `nenhuma tarefa nem lista - plano vazio`() {
        assertTrue(ListOwnership.plan(emptyList(), emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `aplicar o plano e rodar de novo nao muda mais nada`() {
        val tasks = listOf(TaskLink(1, 0, 10), TaskLink(2, 1, 10), TaskLink(3, 2, 404))
        val lists = listOf(list(10), list(11))
        val items = listOf(item(1, 10), item(2, 11), item(3, 88))
        val plan = ListOwnership.plan(tasks, lists, items)

        val tasksAfter = tasks.map { t -> if (t.id in plan.taskLinks) t.copy(listId = plan.taskLinks[t.id]) else t }
        val listsAfter = lists.filter { it.id !in plan.deletedListIds } + plan.newLists
        val itemsAfter = items.filter { it.id !in plan.deletedItemIds } + plan.newItems

        assertTrue("idempotente", ListOwnership.plan(tasksAfter, listsAfter, itemsAfter).isEmpty)
        // Cada tarefa com lista tem a sua; nenhuma lista sem dono.
        val owned = tasksAfter.mapNotNull { it.listId }
        assertEquals(owned.size, owned.toSet().size)
        assertEquals(listsAfter.map { it.id }.toSet(), owned.toSet())
    }
}
