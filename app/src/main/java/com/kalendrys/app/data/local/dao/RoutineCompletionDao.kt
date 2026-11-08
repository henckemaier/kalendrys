package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kalendrys.app.data.local.entity.RoutineCompletion
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineCompletionDao {

    /** Marcar de novo a mesma ocorrência não faz nada (índice único + IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(completion: RoutineCompletion): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(completions: List<RoutineCompletion>)

    @Query("DELETE FROM routine_completion WHERE routineId = :routineId AND epochDay = :epochDay")
    suspend fun delete(routineId: Long, epochDay: Long)

    /** Apaga todas as conclusões de rotina (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM routine_completion")
    suspend fun clear()

    @Query("SELECT * FROM routine_completion WHERE epochDay >= :startEpochDay AND epochDay < :endEpochDay")
    fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<RoutineCompletion>>

    @Query("SELECT routineId FROM routine_completion WHERE epochDay = :epochDay")
    suspend fun doneOn(epochDay: Long): List<Long>

    /** O histórico inteiro, lido uma vez só para a exportação em CSV. */
    @Query("SELECT * FROM routine_completion ORDER BY epochDay ASC, completedAt ASC")
    suspend fun all(): List<RoutineCompletion>

    @Query("SELECT COUNT(*) FROM routine_completion")
    suspend fun count(): Int
}
