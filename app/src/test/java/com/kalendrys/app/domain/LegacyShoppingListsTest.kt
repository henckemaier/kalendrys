package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ShoppingList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesma regra da migração 9→10 (o AppDatabaseMigrationTest confere os mesmos cenários no SQLite). */
class LegacyShoppingListsTest {

    private fun item(id: Long, routineId: Long, picked: Boolean = false, position: Int = 0, createdAt: Long = id * 100) =
        LegacyShoppingLists.LegacyItem(id, routineId, "item$id", quantity = 2, priceCents = 150, picked = picked, position = position, createdAt = createdAt)

    @Test
    fun `uma lista por rotina em ordem crescente de routineId`() {
        val result = LegacyShoppingLists.convert(
            items = listOf(item(10, routineId = 2, createdAt = 900), item(11, routineId = 1, createdAt = 500), item(13, routineId = 2, createdAt = 700)),
            routineTitles = mapOf(1L to "Mercado", 2L to "Feira"),
        )
        assertEquals(
            listOf(ShoppingList(1, "Mercado", 500), ShoppingList(2, "Feira", 700)),
            result.lists,
        )
        assertEquals(mapOf(1L to 1L, 2L to 2L), result.routineLinks)
    }

    @Test
    fun `rotina inexistente vira lista avulsa com o nome padrao`() {
        val result = LegacyShoppingLists.convert(listOf(item(1, routineId = 99)), routineTitles = emptyMap())
        assertEquals(LegacyShoppingLists.FALLBACK_NAME, result.lists.single().name)
        assertTrue(result.routineLinks.isEmpty())
    }

    @Test
    fun `itens preservam id, marcacao, posicao e data`() {
        val source = item(7, routineId = 3, picked = true, position = 4, createdAt = 1234)
        val converted = LegacyShoppingLists.convert(listOf(source), mapOf(3L to "X")).items.single()
        assertEquals(7L, converted.id)
        assertEquals(1L, converted.listId)
        assertEquals(source.name, converted.name)
        assertEquals(2, converted.quantity)
        assertEquals(150L, converted.priceCents)
        assertTrue(converted.picked)
        assertEquals(4, converted.position)
        assertEquals(1234L, converted.createdAt)
        assertEquals(false, converted.pickedBeforeReset)
    }

    @Test
    fun `sem itens nao cria lista, nem para rotina existente`() {
        val result = LegacyShoppingLists.convert(emptyList(), mapOf(1L to "Mercado"))
        assertTrue(result.lists.isEmpty())
        assertTrue(result.items.isEmpty())
        assertTrue(result.routineLinks.isEmpty())
    }
}
