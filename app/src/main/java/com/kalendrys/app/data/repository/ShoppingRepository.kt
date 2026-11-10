package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.entity.ShoppingItem
import kotlinx.coroutines.flow.Flow

/**
 * Lista de compras de uma rotina do tipo `COMPRAS`. Fina sobre o [ShoppingItemDao]: cada edição
 * persiste na hora (a UI reflete via Flow), independente do salvar/descartar do editor da rotina.
 * O total é calculado na leitura ([com.kalendrys.app.domain.ShoppingTotals]), nunca guardado aqui.
 */
class ShoppingRepository(private val dao: ShoppingItemDao) {

    fun observeItems(routineId: Long): Flow<List<ShoppingItem>> = dao.observeByRoutine(routineId)

    /** Novo item no fim da lista (position após o maior atual). Quantidade mínima 1. */
    suspend fun add(routineId: Long, name: String, quantity: Int, priceCents: Long?) {
        val nextPosition = (dao.byRoutine(routineId).maxOfOrNull { it.position } ?: -1) + 1
        dao.insert(
            ShoppingItem(
                routineId = routineId,
                name = name.trim(),
                quantity = quantity.coerceAtLeast(1),
                priceCents = priceCents,
                position = nextPosition,
            ),
        )
    }

    /** Edita nome/quantidade/preço preservando id, routineId, position, picked e createdAt. */
    suspend fun update(item: ShoppingItem, name: String, quantity: Int, priceCents: Long?) =
        dao.update(item.copy(name = name.trim(), quantity = quantity.coerceAtLeast(1), priceCents = priceCents))

    suspend fun setPicked(item: ShoppingItem, picked: Boolean) = dao.update(item.copy(picked = picked))

    suspend fun remove(item: ShoppingItem) = dao.delete(item)
}
