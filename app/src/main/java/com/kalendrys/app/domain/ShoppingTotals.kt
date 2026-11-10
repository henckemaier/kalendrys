package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ShoppingItem

/**
 * Total da lista de compras, CALCULADO na leitura (nunca armazenado): soma de `quantity * priceCents`
 * dos itens que têm preço. Item sem preço ([ShoppingItem.priceCents] nulo) conta zero.
 */
object ShoppingTotals {

    fun totalCents(items: List<ShoppingItem>): Long =
        items.sumOf { item -> item.priceCents?.let { it * item.quantity } ?: 0L }
}
