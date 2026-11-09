package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kalendrys.app.data.local.entity.Routine
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {

    /** Todas, inclusive as excluídas (arquivadas): os dias passados ainda mostram os blocos delas. */
    @Query("SELECT * FROM routines ORDER BY startMinute, id")
    fun observeAll(): Flow<List<Routine>>

    @Query("SELECT * FROM routines")
    suspend fun all(): List<Routine>

    /** Para agendar/alarmar: exclui arquivadas e pausadas — uma rotina pausada não dispara avisos. */
    @Query("SELECT * FROM routines WHERE archivedAt IS NULL AND pausedAt IS NULL")
    suspend fun active(): List<Routine>

    /**
     * Para o retrato do dia (resumo/widgets): exclui as pausadas. Mantém as arquivadas — o
     * [com.kalendrys.app.domain.RoutineSchedule.occursOn] é quem corta cada uma no dia certo.
     */
    @Query("SELECT * FROM routines WHERE pausedAt IS NULL ORDER BY startMinute, id")
    fun observeScheduled(): Flow<List<Routine>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun byId(id: Long): Routine?

    @Query("SELECT * FROM routines WHERE id = :id")
    fun observeById(id: Long): Flow<Routine?>

    @Insert
    suspend fun insert(routine: Routine): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(routines: List<Routine>)

    @Update
    suspend fun update(routine: Routine)

    /** Apaga todas as rotinas (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM routines")
    suspend fun clear()
}
