package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kalendrys.app.data.local.entity.ShoppingItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingItemDao {

    /** Os itens de uma lista na ordem manual; reativo para a UI refletir edições na hora. */
    @Query("SELECT * FROM shopping_item WHERE listId = :listId ORDER BY position, id")
    fun observeByList(listId: Long): Flow<List<ShoppingItem>>

    /** Os itens de uma lista, leitura única (sem Flow). */
    @Query("SELECT * FROM shopping_item WHERE listId = :listId ORDER BY position, id")
    suspend fun byList(listId: Long): List<ShoppingItem>

    /** Progresso e total de cada lista que tem itens (lista vazia não aparece: vale 0/0, total 0). */
    @Query(
        "SELECT listId, COUNT(*) AS total, COALESCE(SUM(picked), 0) AS picked, " +
            "COALESCE(SUM(CASE WHEN priceCents IS NULL THEN 0 ELSE priceCents * quantity END), 0) AS totalCents " +
            "FROM shopping_item GROUP BY listId",
    )
    fun observeStats(): Flow<List<ShoppingListStatsRow>>

    @Insert
    suspend fun insert(item: ShoppingItem): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(items: List<ShoppingItem>)

    @Update
    suspend fun update(item: ShoppingItem)

    @Delete
    suspend fun delete(item: ShoppingItem)

    @Query("DELETE FROM shopping_item WHERE listId = :listId")
    suspend fun deleteByList(listId: Long)

    /** "Desmarcar todos" (ação manual): não mexe no estado de reinício. */
    @Query("UPDATE shopping_item SET picked = 0 WHERE listId = :listId")
    suspend fun clearPicked(listId: Long)

    /** "Desfazer" do "Desmarcar todos": volta a marcar só estes itens (os que ainda existem). */
    @Query("UPDATE shopping_item SET picked = 1 WHERE id IN (:itemIds)")
    suspend fun markPicked(itemIds: List<Long>)

    @Query("UPDATE shopping_item SET pickedBeforeReset = picked, picked = 0 WHERE listId = :listId")
    suspend fun snapshotAndClearPicked(listId: Long)

    @Query("UPDATE shopping_list SET lastResetKey = :key WHERE id = :listId")
    suspend fun setLastResetKey(listId: Long, key: String?)

    @Query("SELECT id FROM shopping_list WHERE lastResetKey = :key")
    suspend fun listIdsWithResetKey(key: String): List<Long>

    @Query("UPDATE shopping_item SET picked = 1 WHERE listId = :listId AND pickedBeforeReset = 1")
    suspend fun repickFromSnapshot(listId: Long)

    @Query("UPDATE shopping_item SET pickedBeforeReset = 0 WHERE listId = :listId")
    suspend fun clearSnapshot(listId: Long)

    /**
     * Reinício reversível (ADR-0001, 4.4): guarda as marcações em `pickedBeforeReset`, zera
     * `picked` e grava a [key] na lista. Um reinício posterior troca a chave. Lista inexistente
     * (vínculo órfão) não afeta nenhuma linha.
     */
    @Transaction
    suspend fun resetPicked(listId: Long, key: String) {
        snapshotAndClearPicked(listId)
        setLastResetKey(listId, key)
    }

    /**
     * Desfaz o reinício identificado por [key], se ainda for o mais recente da lista: volta a marcar
     * o que estava marcado (união com o que foi marcado depois). Sem lista com essa chave, é no-op.
     */
    @Transaction
    suspend fun restoreReset(key: String) {
        for (listId in listIdsWithResetKey(key)) {
            repickFromSnapshot(listId)
            clearSnapshot(listId)
            setLastResetKey(listId, null)
        }
    }

    /** Todos os itens, leitura única para a exportação do backup. */
    @Query("SELECT * FROM shopping_item ORDER BY listId, position, id")
    suspend fun all(): List<ShoppingItem>

    /** Apaga todos os itens (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM shopping_item")
    suspend fun clear()
}
