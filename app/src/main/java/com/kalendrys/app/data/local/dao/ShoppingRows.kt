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

/**
 * Uma tarefa excluída com a lista dela e os itens, como estavam (v1.10.0, ADR-0002 16.10.4): o que o
 * Desfazer guarda em memória e devolve junto. [list] nulo = tarefa sem lista (ou vínculo órfão).
 */
data class TaskWithList(
    val task: com.kalendrys.app.data.local.entity.Task,
    val list: com.kalendrys.app.data.local.entity.ShoppingList?,
    val items: List<com.kalendrys.app.data.local.entity.ShoppingItem>,
)
