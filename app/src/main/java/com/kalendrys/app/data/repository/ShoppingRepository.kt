package com.kalendrys.app.data.repository

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.dao.ShoppingListOwnerRow
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.domain.ShoppingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
class ShoppingRepository(
    private val database: AppDatabase,
    /** O relógio do app (v1.10.0, 16.3): o carimbo de criação de listas e itens. */
    private val clock: AppClock,
) {

    private val listDao = database.shoppingListDao()
    private val itemDao = database.shoppingItemDao()
    private val taskDao = database.taskDao()

    // --- Listas ---------------------------------------------------------------------------------

    fun observeLists(): Flow<List<ShoppingList>> = listDao.observeAll()

    fun observeList(listId: Long): Flow<ShoppingList?> = listDao.observeById(listId)

    suspend fun list(listId: Long): ShoppingList? = listDao.byId(listId)

    /**
     * Cria uma lista SEM tarefa e devolve o id. Desde a v1.10.0 (ADR-0002 16.10.4) nenhum caminho do app
     * faz isso: a lista nasce com a tarefa (TaskChange.NewShoppingList, addTask com newListName). Fica só
     * para os testes montarem dados.
     */
    @VisibleForTesting
    suspend fun createList(name: String): Long = listDao.insert(ShoppingList.named(name, clock.nowMillis()))

    suspend fun renameList(listId: Long, name: String) {
        if (name.isBlank()) return
        listDao.rename(listId, name.trim())
    }

    /**
     * "Excluir lista" (v1.10.0, P45): apaga a lista, os itens e o vínculo da tarefa dona, numa transação
     * só. Não sobra lista sem tarefa nem tarefa apontando para lista que não existe.
     */
    suspend fun deleteList(listId: Long) = database.withTransaction {
        itemDao.deleteByList(listId)
        taskDao.clearShoppingList(listId)
        listDao.delete(listId)
    }

    /** Todas as listas com progresso, total e donos, na ordem de criação. */
    fun observeSummaries(): Flow<List<ShoppingListSummary>> =
        combine(
            listDao.observeAll(),
            itemDao.observeStats(),
            taskDao.observeShoppingListOwners(),
        ) { lists, stats, taskOwners ->
            val statsById = stats.associateBy { it.listId }
            val owners = owners(taskOwners)
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

    /**
     * Progresso de TODAS as listas existentes (listId → marcados/total; lista vazia = 0/0), para o
     * retrato do dia ([com.kalendrys.app.domain.TodayOverview]). Um `shoppingListId` que não está no
     * mapa é vínculo órfão: sem progresso.
     */
    fun observeProgressByList(): Flow<Map<Long, ShoppingProgress>> =
        combine(listDao.observeAll(), itemDao.observeStats()) { lists, stats ->
            val statsById = stats.associateBy { it.listId }
            lists.associate { list ->
                list.id to (statsById[list.id]?.let { ShoppingProgress(it.picked, it.total) } ?: ShoppingProgress.EMPTY)
            }
        }.distinctUntilChanged()

    /**
     * Progresso atual de uma lista, para textos fora da UI (notificações). `null` sem vínculo ou com
     * vínculo órfão (lista inexistente), que equivalem a "sem lista".
     */
    suspend fun progressOf(listId: Long?): ShoppingProgress? =
        listId?.let { observeProgressByList().first()[it] }

    /** Resumo de uma lista; `null` quando ela não existe (vínculo órfão equivale a "sem lista"). */
    fun observeSummary(listId: Long): Flow<ShoppingListSummary?> =
        observeSummaries().map { all -> all.firstOrNull { it.id == listId } }.distinctUntilChanged()

    /** Donos de cada lista: só tarefas desde a v1.10.0 (rotina não tem lista; ADR-0002 16.10.1). */
    private fun owners(taskOwners: List<ShoppingListOwnerRow>): Map<Long, List<ShoppingListOwner>> =
        taskOwners.groupBy({ it.shoppingListId }, { ShoppingListOwner(ShoppingListOwner.Kind.TASK, it.id, it.title) })

    // --- Itens ----------------------------------------------------------------------------------

    fun observeItems(listId: Long): Flow<List<ShoppingItem>> = itemDao.observeByList(listId)

    /**
     * Novo item no fim da lista (position após a maior atual). Quantidade mínima 1. Ler a maior e
     * inserir na mesma transação: duas inclusões ao mesmo tempo (adição rápida com Enter seguido) não
     * pegam a mesma posição (parecer do Esquadro, E4).
     */
    suspend fun add(listId: Long, name: String, quantity: Int, priceCents: Long?): Long = database.withTransaction {
        val nextPosition = (itemDao.maxPosition(listId) ?: -1) + 1
        itemDao.insert(
            ShoppingItem(
                listId = listId,
                name = name.trim(),
                quantity = quantity.coerceAtLeast(1),
                priceCents = priceCents,
                position = nextPosition,
                createdAt = clock.nowMillis(),
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

    /** "Desfazer" do "Desmarcar todos": remarca [itemIds]; itens removidos no meio tempo são ignorados. */
    suspend fun markPicked(itemIds: List<Long>) {
        if (itemIds.isNotEmpty()) itemDao.markPicked(itemIds)
    }

    // --- Reinício reversível (P4) ---------------------------------------------------------------

    /** Zera as marcações guardando-as sob [key] (ver [com.kalendrys.app.domain.ShoppingReset]). */
    suspend fun resetPicked(listId: Long, key: String) = itemDao.resetPicked(listId, key)

    /** Desfaz o reinício [key], se ainda for o mais recente da lista; senão é no-op. */
    suspend fun restoreReset(key: String) = itemDao.restoreReset(key)
}
