package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kalendrys.app.data.local.entity.ExerciseItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseItemDao {

    /** Os exercícios de uma rotina na ordem manual da lista; reativo para a UI refletir edições na hora. */
    @Query("SELECT * FROM exercise_item WHERE routineId = :routineId ORDER BY position, id")
    fun observeByRoutine(routineId: Long): Flow<List<ExerciseItem>>

    /** A lista de uma rotina, leitura única (sem Flow). */
    @Query("SELECT * FROM exercise_item WHERE routineId = :routineId ORDER BY position, id")
    suspend fun byRoutine(routineId: Long): List<ExerciseItem>

    @Insert
    suspend fun insert(item: ExerciseItem): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(items: List<ExerciseItem>)

    @Update
    suspend fun update(item: ExerciseItem)

    @Delete
    suspend fun delete(item: ExerciseItem)

    /** Todos os itens, leitura única para a exportação do backup. */
    @Query("SELECT * FROM exercise_item ORDER BY routineId, position, id")
    suspend fun all(): List<ExerciseItem>

    /** Apaga todos os itens (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM exercise_item")
    suspend fun clear()
}
