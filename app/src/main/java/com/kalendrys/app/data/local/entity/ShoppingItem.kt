package com.kalendrys.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Um item de uma [ShoppingList]. [position] dá a ordem manual; [picked] marca o item como pego no
 * carrinho; [quantity] e [priceCents] (em centavos, nullable) alimentam o TOTAL, que é CALCULADO na
 * leitura, nunca armazenado.
 *
 * [pickedBeforeReset] guarda o [picked] de antes do último reinício da lista, para o "Desfazer" da
 * conclusão poder devolver as marcações (ADR-0001, seção 4.4).
 *
 * Sem foreign key, como o resto do banco. O índice por [listId] serve às consultas por lista.
 */
@Entity(
    tableName = "shopping_item",
    indices = [Index("listId")],
)
data class ShoppingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val name: String,
    val quantity: Int = 1,
    val priceCents: Long? = null,
    val picked: Boolean = false,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val pickedBeforeReset: Boolean = false,
)
