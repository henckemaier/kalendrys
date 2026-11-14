package com.kalendrys.app.data.repository

import androidx.room.withTransaction
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.dao.ShoppingListOwnerRow
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.domain.ShoppingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Quem usa uma lista: rotina (não arquivada) ou tarefa. */
data class ShoppingListOwner(val kind: Kind, val id: Long, val title: String) {
    enum class Kind { ROUTINE, TASK }
}

/** Resumo de uma lista para a aba Listas e para os cartões (editor de rotina, detalhe da tarefa). */
data class ShoppingListSummary(
    val list: ShoppingList,
    val progress: ShoppingProgress,
    /** Soma de quantity * priceCents (item sem preço conta zero). */
    val totalCents: Long,
    /** Rotinas primeiro, depois tarefas, cada grupo por título. Vazio = lista avulsa. */
    val owners: List<ShoppingListOwner>,
) {
    val id: Long get() = list.id
    val name: String get() = list.name
}

/**
 * Fonte única das listas de compras e dos itens (ADR-0001). Cada edição persiste na hora (a UI
 * reflete via Flow), sem depender do salvar/descartar de nenhum editor. O total é calculado na
 * leitura, nunca guardado. Os vínculos (`shoppingListId`) moram nos donos; aqui só se desfazem ao
 * excluir a lista.
 */
class ShoppingRepository(private val database: AppDatabase) {

    private val listDao = database.shoppingListDao()
    private val itemDao = database.shoppingItemDao()
    private val routineDao = database.routineDao()
    private val taskDao = database.taskDao()

    // --- Listas ---------------------------------------------------------------------------------

    fun observeLists(): Flow<List<ShoppingList>> = listDao.observeAll()

    fun observeList(listId: Long): Flow<ShoppingList?> = listDao.observeById(listId)

    suspend fun list(listId: Long): ShoppingList? = listDao.byId(listId)

    /** Cria uma lista avulsa e devolve o id. Nome vazio não é aceito (a UI valida antes). */
    suspend fun createList(name: String): Long = listDao.insert(ShoppingList(name = name.trim()))

    suspend fun renameList(listId: Long, name: String) {
        if (name.isBlank()) return
        listDao.rename(listId, name.trim())
    }

    /**
     * Exclui a lista, os itens dela e o vínculo de todas as tarefas e rotinas (inclusive arquivadas),
     * numa transação só (padrão do [RoutineCategoryRepository.delete]).
     */
    suspend fun deleteList(listId: Long) = database.withTransaction {
        itemDao.deleteByList(listId)
        routineDao.clearShoppingList(listId)
        taskDao.clearShoppingList(listId)
        listDao.delete(listId)
    }

    /** Todas as listas com progresso, total e donos, na ordem de criação. */
    fun observeSummaries(): Flow<List<ShoppingListSummary>> =
        combine(
            listDao.observeAll(),
            itemDao.observeStats(),
            routineDao.observeShoppingListOwners(),
            taskDao.observeShoppingListOwners(),
        ) { lists, stats, routineOwners, taskOwners ->
            val statsById = stats.associateBy { it.listId }
            val owners = owners(routineOwners, taskOwners)
            lists.map { list ->
                val s = statsById[list.id]
                ShoppingListSummary(
                    list = list,
                    progress = s?.let { ShoppingProgress(it.picked, it.total) } ?: ShoppingProgress.EMPTY,
                    totalCents = s?.totalCents ?: 0L,
                    owners = owners[list.id].orEmpty(),
                )
            }
        }

    /** Resumo de uma lista; `null` quando ela não existe (vínculo órfão equivale a "sem lista"). */
    fun observeSummary(listId: Long): Flow<ShoppingListSummary?> =
        observeSummaries().map { all -> all.firstOrNull { it.id == listId } }.distinctUntilChanged()

    private fun owners(
        routineOwners: List<ShoppingListOwnerRow>,
        taskOwners: List<ShoppingListOwnerRow>,
    ): Map<Long, List<ShoppingListOwner>> {
        val routines = routineOwners.map { it.shoppingListId to ShoppingListOwner(ShoppingListOwner.Kind.ROUTINE, it.id, it.title) }
        val tasks = taskOwners.map { it.shoppingListId to ShoppingListOwner(ShoppingListOwner.Kind.TASK, it.id, it.title) }
        return (routines + tasks).groupBy({ it.first }, { it.second })
    }

    // --- Itens ----------------------------------------------------------------------------------

    fun observeItems(listId: Long): Flow<List<ShoppingItem>> = itemDao.observeByList(listId)

    /** Novo item no fim da lista (position após o maior atual). Quantidade mínima 1. */
    suspend fun add(listId: Long, name: String, quantity: Int, priceCents: Long?): Long {
        val nextPosition = (itemDao.byList(listId).maxOfOrNull { it.position } ?: -1) + 1
        return itemDao.insert(
            ShoppingItem(
                listId = listId,
                name = name.trim(),
                quantity = quantity.coerceAtLeast(1),
                priceCents = priceCents,
                position = nextPosition,
            ),
        )
    }

    /** Edita nome/quantidade/preço preservando id, listId, position, picked e createdAt. */
    suspend fun update(item: ShoppingItem, name: String, quantity: Int, priceCents: Long?) =
        itemDao.update(item.copy(name = name.trim(), quantity = quantity.coerceAtLeast(1), priceCents = priceCents))

    suspend fun setPicked(item: ShoppingItem, picked: Boolean) = itemDao.update(item.copy(picked = picked))

    suspend fun remove(item: ShoppingItem) = itemDao.delete(item)

    /** "Desfazer" da remoção: reinsere o item como estava (mesmo id, posição e marcação). */
    suspend fun reinsert(item: ShoppingItem) {
        itemDao.insert(item)
    }

    /** "Desmarcar todos" (manual), para listas avulsas e de tarefa única. */
    suspend fun clearPicked(listId: Long) = itemDao.clearPicked(listId)

    // --- Reinício reversível (P4) ---------------------------------------------------------------

    /** Zera as marcações guardando-as sob [key] (ver [com.kalendrys.app.domain.ShoppingReset]). */
    suspend fun resetPicked(listId: Long, key: String) = itemDao.resetPicked(listId, key)

    /** Desfaz o reinício [key], se ainda for o mais recente da lista; senão é no-op. */
    suspend fun restoreReset(key: String) = itemDao.restoreReset(key)
}
