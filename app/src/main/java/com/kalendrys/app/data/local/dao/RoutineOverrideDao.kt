package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kalendrys.app.data.local.entity.RoutineOverride
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineOverrideDao {

    @Query("SELECT * FROM routine_override ORDER BY epochDay, id")
    suspend fun all(): List<RoutineOverride>

    /** As exceções de forma reativa, para o display (hub/calendário) refletir skip/remarcação na hora. */
    @Query("SELECT * FROM routine_override ORDER BY epochDay, id")
    fun observeAll(): Flow<List<RoutineOverride>>

    /** Uma exceção por rotina/dia (índice único); regravar a mesma substitui a anterior. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: RoutineOverride): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(overrides: List<RoutineOverride>)

    @Update
    suspend fun update(override: RoutineOverride)

    @Delete
    suspend fun delete(override: RoutineOverride)

    /** Apaga todas as exceções (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM routine_override")
    suspend fun clear()
}
