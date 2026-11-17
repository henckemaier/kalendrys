package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kalendrys.app.data.local.entity.CompletionLog
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

    /** Tarefas vinculadas a uma lista de compras (oferta "Concluir tarefa", ADR-0001 P3). */
    @Query("SELECT * FROM tasks WHERE shoppingListId = :listId ORDER BY id")
    suspend fun byShoppingList(listId: Long): List<Task>

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

    @Insert
    suspend fun insertCompletionLog(log: CompletionLog): Long

    /**
     * Conclusão ADIANTADA de tarefa única (banco 11): grava a data reescrita da tarefa e o registro
     * com o que ela sobrescreveu numa transação só. Sem ela, uma falha entre os dois deixaria a data
     * trocada sem registro para o Desfazer devolver.
     */
    @Transaction
    suspend fun rewriteAndLogCompletion(task: Task, log: CompletionLog): Long {
        update(task)
        return insertCompletionLog(log)
    }

    @Delete
    suspend fun delete(task: Task)

    /** Tarefas com lista anexada (etiqueta de dono na aba Listas e no cartão). */
    @Query("SELECT id, title, shoppingListId FROM tasks WHERE shoppingListId IS NOT NULL ORDER BY title, id")
    fun observeShoppingListOwners(): Flow<List<ShoppingListOwnerRow>>

    /** Desvincula as tarefas de uma lista (ao excluí-la); rode dentro de uma transação. */
    @Query("UPDATE tasks SET shoppingListId = NULL WHERE shoppingListId = :listId")
    suspend fun clearShoppingList(listId: Long)

    /** Apaga todas as tarefas (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM tasks")
    suspend fun clear()
}
