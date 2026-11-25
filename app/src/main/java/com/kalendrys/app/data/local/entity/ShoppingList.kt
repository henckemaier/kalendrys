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
    val createdAt: Long,
    val lastResetKey: String? = null,
) {
    companion object {
        /**
         * A lista nova como o app a cria, por QUALQUER caminho (aba Listas pelo
         * [com.kalendrys.app.data.repository.ShoppingRepository.createList] e "Criar nova" no painel de
         * nova tarefa, junto com a tarefa): nome aparado, sem reinício. Uma regra só; não monte a
         * entidade de outro jeito. Nome em branco não é lista: quem chama não cria.
         */
        fun named(name: String, createdAt: Long): ShoppingList = ShoppingList(name = name.trim(), createdAt = createdAt)
    }
}
