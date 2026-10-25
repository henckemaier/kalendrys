package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kalendrys.app.data.local.entity.CompletionLog
import kotlinx.coroutines.flow.Flow

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

    /** O histórico inteiro, lido uma vez só para a exportação em CSV. */
    @Query("SELECT * FROM completion_log ORDER BY completedAt ASC")
    suspend fun all(): List<CompletionLog>

    @Query("SELECT COUNT(*) FROM completion_log")
    suspend fun count(): Int
}
