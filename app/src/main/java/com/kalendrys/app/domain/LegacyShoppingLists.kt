package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList

/**
 * Converte a lista de compras antiga (itens presos a um `routineId`, Room ≤ v9 / backup ≤ v5) no
 * formato de listas independentes (ADR-0001, 4.2). Regra ÚNICA, usada tanto pela migração 9→10
 * quanto pelo restauro de backup antigo, para os dois darem o mesmo resultado:
 *
 * - uma lista por `routineId` distinto, em ordem crescente de `routineId`, com ids [firstListId],
 *   [firstListId] + 1, …;
 * - nome = título da rotina; rotina inexistente → [FALLBACK_NAME] (e a lista fica avulsa);
 * - `createdAt` = menor `createdAt` dos itens daquela rotina;
 * - vínculo rotina → lista para toda rotina existente (inclusive arquivada);
 * - itens com id, nome, quantidade, preço, marcação, posição e `createdAt` preservados.
 *
 * ATENÇÃO (v11+): a `AppDatabase.MIGRATION_9_10` usa esta conversão e grava as colunas de
 * [ShoppingList] e [ShoppingItem] com SQL fixo no formato da v10. Se essas entidades mudarem
 * (coluna nova, renomeada ou removida), a migração 9→10 tem de ser revista: ela precisa continuar
 * produzindo exatamente o schema `10.json`, e as migrações seguintes partem dele. O mesmo vale para
 * o restauro de backup ≤ v5, que reaproveita o resultado daqui.
 */
object LegacyShoppingLists {

    const val FALLBACK_NAME = "Lista de compras"

    /** Item no formato antigo. */
    data class LegacyItem(
        val id: Long,
        val routineId: Long,
        val name: String,
        val quantity: Int,
        val priceCents: Long?,
        val picked: Boolean,
        val position: Int,
        val createdAt: Long,
    )

    data class Result(
        val lists: List<ShoppingList>,
        val items: List<ShoppingItem>,
        /** routineId → listId, só para rotinas que existem. */
        val routineLinks: Map<Long, Long>,
    )

    fun convert(items: List<LegacyItem>, routineTitles: Map<Long, String>, firstListId: Long = 1): Result {
        val lists = mutableListOf<ShoppingList>()
        val converted = mutableListOf<ShoppingItem>()
        val links = linkedMapOf<Long, Long>()
        var nextId = firstListId
        for ((routineId, group) in items.groupBy { it.routineId }.toSortedMap()) {
            val listId = nextId++
            val title = routineTitles[routineId]
            lists += ShoppingList(
                id = listId,
                name = title ?: FALLBACK_NAME,
                createdAt = group.minOf { it.createdAt },
            )
            if (title != null) links[routineId] = listId
            group.sortedBy { it.id }.mapTo(converted) { item ->
                ShoppingItem(
                    id = item.id,
                    listId = listId,
                    name = item.name,
                    quantity = item.quantity,
                    priceCents = item.priceCents,
                    picked = item.picked,
                    position = item.position,
                    createdAt = item.createdAt,
                )
            }
        }
        return Result(lists, converted, links)
    }
}
