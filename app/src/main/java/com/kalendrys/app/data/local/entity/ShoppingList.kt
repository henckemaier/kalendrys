package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lista de compras independente (ADR-0001). Os itens ([ShoppingItem.listId]) apontam para ela; os
 * donos (rotinas e tarefas) guardam o vínculo do lado deles (`shoppingListId`). Lista sem dono é
 * uma lista avulsa. Sem foreign key: um `shoppingListId` que aponta para lista inexistente equivale
 * a "sem lista".
 *
 * [lastResetKey] identifica o reinício mais recente das marcações (ver
 * [com.kalendrys.app.domain.ShoppingReset]); só ele pode ser desfeito.
 */
@Entity(tableName = "shopping_list")
data class ShoppingList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastResetKey: String? = null,
)
