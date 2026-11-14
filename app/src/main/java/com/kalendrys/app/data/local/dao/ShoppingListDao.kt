package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kalendrys.app.data.local.entity.ShoppingList
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingListDao {

    /** Todas as listas, das mais antigas para as mais novas (ordem estável para a aba). */
    @Query("SELECT * FROM shopping_list ORDER BY createdAt, id")
    fun observeAll(): Flow<List<ShoppingList>>

    @Query("SELECT * FROM shopping_list WHERE id = :id")
    fun observeById(id: Long): Flow<ShoppingList?>

    @Query("SELECT * FROM shopping_list WHERE id = :id")
    suspend fun byId(id: Long): ShoppingList?

    @Insert
    suspend fun insert(list: ShoppingList): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(lists: List<ShoppingList>)

    @Query("UPDATE shopping_list SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM shopping_list WHERE id = :id")
    suspend fun delete(id: Long)

    /** Todas as listas, leitura única para a exportação do backup. */
    @Query("SELECT * FROM shopping_list ORDER BY id")
    suspend fun all(): List<ShoppingList>

    /** Apaga todas as listas (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM shopping_list")
    suspend fun clear()
}
