package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kalendrys.app.data.local.entity.CompletionLog
import kotlinx.coroutines.flow.Flow

/** Linha de [CompletionLogDao.observeLastCompletions]. */
data class LastCompletion(val taskId: Long, val completedAt: Long)

@Dao
interface CompletionLogDao {

    @Insert
    suspend fun insert(log: CompletionLog): Long

    @Query("DELETE FROM completion_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Conclusões em [start, end) — usa o índice de completedAt; nunca carrega o histórico todo. */
    @Query("SELECT * FROM completion_log WHERE completedAt >= :start AND completedAt < :end ORDER BY completedAt ASC")
    fun observeBetween(start: Long, end: Long): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_log WHERE taskId = :taskId ORDER BY completedAt DESC")
    fun observeForTask(taskId: Long): Flow<List<CompletionLog>>

    /** Última conclusão de cada tarefa: decide se uma tarefa sem repetição está encerrada. */
    @Query("SELECT taskId, MAX(completedAt) AS completedAt FROM completion_log GROUP BY taskId")
    fun observeLastCompletions(): Flow<List<LastCompletion>>

    @Query("SELECT taskId, MAX(completedAt) AS completedAt FROM completion_log GROUP BY taskId")
    suspend fun lastCompletions(): List<LastCompletion>

    @Query("SELECT MAX(completedAt) FROM completion_log WHERE taskId = :taskId")
    suspend fun lastCompletion(taskId: Long): Long?

    @Query("SELECT * FROM completion_log WHERE id = :id")
    suspend fun byId(id: Long): CompletionLog?

    @Query("SELECT EXISTS(SELECT 1 FROM completion_log WHERE taskId = :taskId AND completedAt >= :start AND completedAt < :end)")
    suspend fun isCompletedBetween(taskId: Long, start: Long, end: Long): Boolean

    /** O histórico inteiro, lido uma vez só para a exportação em CSV. */
    @Query("SELECT * FROM completion_log ORDER BY completedAt ASC")
    suspend fun all(): List<CompletionLog>

    @Query("SELECT COUNT(*) FROM completion_log")
    suspend fun count(): Int
}
