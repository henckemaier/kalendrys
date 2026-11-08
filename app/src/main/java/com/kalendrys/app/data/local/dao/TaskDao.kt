package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kalendrys.app.data.local.entity.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): Task?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<Task?>

    /** Lembretes únicos ainda no futuro e todos os recorrentes (sempre têm uma próxima ocorrência). */
    @Query("SELECT * FROM tasks WHERE reminderAt IS NOT NULL AND (reminderAt > :now OR reminderRepeat != 'NONE')")
    suspend fun withPendingReminder(now: Long): List<Task>

    @Insert
    suspend fun insert(task: Task): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(tasks: List<Task>)

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    /** Apaga todas as tarefas (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM tasks")
    suspend fun clear()
}
