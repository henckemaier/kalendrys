package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kalendrys.app.data.local.entity.ShoppingItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingItemDao {

    /** Os itens de uma rotina na ordem manual da lista; reativo para a UI refletir edições na hora. */
    @Query("SELECT * FROM shopping_item WHERE routineId = :routineId ORDER BY position, id")
    fun observeByRoutine(routineId: Long): Flow<List<ShoppingItem>>

    /** A lista de uma rotina, leitura única (sem Flow). */
    @Query("SELECT * FROM shopping_item WHERE routineId = :routineId ORDER BY position, id")
    suspend fun byRoutine(routineId: Long): List<ShoppingItem>

    @Insert
    suspend fun insert(item: ShoppingItem): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(items: List<ShoppingItem>)

    @Update
    suspend fun update(item: ShoppingItem)

    @Delete
    suspend fun delete(item: ShoppingItem)

    /** Apaga os itens de UMA rotina (restauro do snapshot ao descartar o editor). */
    @Query("DELETE FROM shopping_item WHERE routineId = :routineId")
    suspend fun deleteByRoutine(routineId: Long)

    /**
     * Restaura a lista de uma rotina exatamente como [items] (descartar edições de item no editor):
     * limpa a rotina e reinsere o snapshot preservando ids/position/picked/createdAt via [insertAll].
     * Atômico, para a lista nunca ficar meio-trocada.
     */
    @Transaction
    suspend fun replaceForRoutine(routineId: Long, items: List<ShoppingItem>) {
        deleteByRoutine(routineId)
        insertAll(items)
    }

    /** Todos os itens, leitura única para a exportação do backup. */
    @Query("SELECT * FROM shopping_item ORDER BY routineId, position, id")
    suspend fun all(): List<ShoppingItem>

    /** Apaga todos os itens (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM shopping_item")
    suspend fun clear()
}
