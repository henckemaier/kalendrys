package com.kalendrys.app.data.local.dao

/** Dono de uma lista de compras (rotina ou tarefa), lido só com o necessário para a etiqueta. */
data class ShoppingListOwnerRow(
    val id: Long,
    val title: String,
    val shoppingListId: Long,
)

/** Agregado por lista: quantos itens, quantos marcados e o total em centavos (quantity * priceCents). */
data class ShoppingListStatsRow(
    val listId: Long,
    val total: Int,
    val picked: Int,
    val totalCents: Long,
)
