package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList

/**
 * Regras ÚNICAS de dados da v1.10.0 (ADR-0002 16.10.3; ADR-0001 seção 9): a lista pertence a UMA
 * tarefa só, rotina não tem lista e tem tipo. Usadas pela migração 11→12 (`AppDatabase.MIGRATION_11_12`)
 * e pelo restauro de backup (`BackupManager`), para os dois caminhos darem o mesmo resultado com os
 * mesmos dados; o teste cruzado confere.
 *
 * - [kindFromCategory] é o passo 1 (tipo pela categoria). Só para dado de ANTES do tipo existir
 *   (banco 11, backup ≤ 6): no backup 7 o tipo já vem gravado e não se recalcula.
 * - [plan] são os passos 3 a 6, idempotentes: aplicados a dado já correto (banco 12, backup 7), não
 *   mudam nada.
 *
 * Puro: recebe as linhas lidas e devolve o que mudar; quem chama aplica (SQL na migração, entidades
 * no restauro).
 *
 * CONGELADAS (A3 da revisão da T1): [kindFromCategory] e [plan] são a regra do banco 12 e do backup 7,
 * já publicada na migração. Não mudam: uma regra nova é uma função NOVA (e uma migração nova), senão o
 * aparelho que migrou com a regra antiga e o restauro com a nova divergem. O `Migration11To12Test`,
 * com bancos fixos de cada caso, não pode ser afrouxado.
 */
object ListOwnership {

    /** Passo 1: rotina em categoria do tipo Exercício vira Exercício; qualquer outra (ou sem), Nenhum. */
    fun kindFromCategory(categoryKind: String?): RoutineKind =
        if (categoryKind == RoutineCategoryKind.EXERCICIO.name) RoutineKind.EXERCISE else RoutineKind.NONE

    /** Uma tarefa como as regras a veem: a mais antiga é a de menor [createdAt] (empate: menor [id]). */
    data class TaskLink(val id: Long, val createdAt: Long, val listId: Long?)

    data class Plan(
        /** Tarefas cujo vínculo muda: id → lista nova (`null` = sem lista). Só as que mudam. */
        val taskLinks: Map<Long, Long?>,
        /** Cópias criadas no passo 4, com ids novos acima dos existentes. */
        val newLists: List<ShoppingList>,
        val newItems: List<ShoppingItem>,
        /** Passo 6: listas sem tarefa (com os itens delas, em [deletedItemIds]). */
        val deletedListIds: Set<Long>,
        /** Passo 6: itens das listas apagadas e itens sem lista. */
        val deletedItemIds: Set<Long>,
    ) {
        val isEmpty: Boolean
            get() = taskLinks.isEmpty() && newLists.isEmpty() && newItems.isEmpty() &&
                deletedListIds.isEmpty() && deletedItemIds.isEmpty()
    }

    /**
     * Passos 3 a 6 sobre as linhas atuais. O passo 3 (apagar o vínculo de lista de TODAS as rotinas)
     * não depende de nada e quem chama o aplica sempre; as rotinas não entram aqui, porque o que elas
     * apontavam deixa de contar como dono (uma lista só de rotina fica sem tarefa e cai no passo 6).
     *
     * 4. Lista usada por mais de uma tarefa: fica com a mais antiga; cada uma das outras recebe uma
     *    cópia da lista com os itens (P44 do usuário), com ids novos acima dos existentes.
     * 5. Vínculo de tarefa para lista que não existe: limpo.
     * 6. Lista sem tarefa: apagada com os itens. Itens sem lista: apagados.
     */
    fun plan(
        tasks: List<TaskLink>,
        lists: List<ShoppingList>,
        items: List<ShoppingItem>,
    ): Plan {
        val listsById = lists.associateBy { it.id }
        val itemsByList = items.groupBy { it.listId }
        val taskLinks = linkedMapOf<Long, Long?>()
        val newLists = mutableListOf<ShoppingList>()
        val newItems = mutableListOf<ShoppingItem>()
        var nextListId = (lists.maxOfOrNull { it.id } ?: 0L) + 1
        var nextItemId = (items.maxOfOrNull { it.id } ?: 0L) + 1

        // 5. Vínculo órfão.
        for (task in tasks.sortedBy { it.id }) {
            if (task.listId != null && task.listId !in listsById) taskLinks[task.id] = null
        }

        // 4. Lista compartilhada: em ordem de lista, e dentro dela da tarefa mais antiga para a mais nova.
        val owners = tasks.filter { it.listId != null && it.listId in listsById }.groupBy { it.listId!! }
        for ((listId, sharing) in owners.toSortedMap()) {
            if (sharing.size < 2) continue
            val others = sharing.sortedWith(compareBy<TaskLink>({ it.createdAt }, { it.id })).drop(1)
            val original = listsById.getValue(listId)
            val originalItems = itemsByList[listId].orEmpty().sortedWith(compareBy({ it.position }, { it.id }))
            for (task in others) {
                val copyId = nextListId++
                // A cópia nasce sem reinício pendente: o Desfazer de uma conclusão da outra tarefa não a
                // alcança (a chave do reinício é da lista original).
                newLists += original.copy(id = copyId, lastResetKey = null)
                originalItems.mapTo(newItems) { item ->
                    item.copy(id = nextItemId++, listId = copyId, pickedBeforeReset = false)
                }
                taskLinks[task.id] = copyId
            }
        }

        // 6. Lista sem tarefa (depois dos passos 4 e 5) e itens sem lista.
        val owned = tasks.mapNotNull { task -> if (task.id in taskLinks) taskLinks[task.id] else task.listId }.toSet()
        val deletedListIds = lists.map { it.id }.filterTo(linkedSetOf()) { it !in owned }
        val surviving = listsById.keys - deletedListIds
        val deletedItemIds = items.filter { it.listId !in surviving }.mapTo(linkedSetOf()) { it.id }

        return Plan(taskLinks, newLists, newItems, deletedListIds, deletedItemIds)
    }
}
