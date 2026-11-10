package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Um item da lista de compras de uma rotina do tipo `COMPRAS` ([RoutineCategoryKind.COMPRAS]).
 * [routineId] liga o item à rotina; [position] dá a ordem manual na lista; [picked] marca o item
 * como pego no carrinho; [quantity] e [priceCents] (em centavos, nullable) alimentam o TOTAL —
 * que é CALCULADO na leitura (soma de `quantity * priceCents`), nunca armazenado numa coluna.
 *
 * Sem foreign key, como o [RoutineCompletion]/[RoutineOverride]: o item sobrevive à exclusão da
 * rotina e entra no backup. O índice por [routineId] serve às consultas da lista por rotina.
 */
@Entity(
    tableName = "shopping_item",
    indices = [Index("routineId")],
)
data class ShoppingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val name: String,
    val quantity: Int = 1,
    val priceCents: Long? = null,
    val picked: Boolean = false,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
