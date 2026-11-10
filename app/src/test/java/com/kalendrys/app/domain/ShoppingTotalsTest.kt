package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingTotalsTest {

    private fun item(quantity: Int, priceCents: Long?) =
        ShoppingItem(routineId = 1, name = "x", quantity = quantity, priceCents = priceCents, position = 0)

    @Test
    fun `soma quantity vezes priceCents dos itens com preco`() {
        val items = listOf(item(2, 499), item(1, 1000))
        // 2*499 + 1*1000 = 1998
        assertEquals(1998L, ShoppingTotals.totalCents(items))
    }

    @Test
    fun `itens sem preco contam zero`() {
        val items = listOf(item(3, null), item(2, 250))
        assertEquals(500L, ShoppingTotals.totalCents(items))
    }

    @Test
    fun `lista vazia tem total zero`() {
        assertEquals(0L, ShoppingTotals.totalCents(emptyList()))
    }
}
