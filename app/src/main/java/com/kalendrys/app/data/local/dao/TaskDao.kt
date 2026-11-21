package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kalendrys.app.data.local.entity.ShoppingList
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

    /**
     * Grava a lista SÓ dentro de [insertTaskWithNewList] (USO RESTRITO). Fora dela a lista se cria
     * pelo [com.kalendrys.app.data.repository.ShoppingRepository.createList]; não chame daqui.
     */
    @Insert
    suspend fun insertList(list: ShoppingList): Long

    /**
     * "Criar nova" lista no painel de nova tarefa (v1.8.0, E6): grava a lista e a tarefa já vinculada
     * a ela numa transação só e devolve o id da tarefa. Sem ela, uma falha ao gravar a tarefa deixava
     * uma lista avulsa que ninguém pediu. [list] vem de [ShoppingList.named]; o [Task.shoppingListId]
     * de [task] é trocado pelo da lista gravada. Só DAO aqui dentro: trava, agendamento do lembrete e
     * catch-up ficam com o repositório, fora da transação.
     */
    @Transaction
    suspend fun insertTaskWithNewList(list: ShoppingList, task: Task): Long {
        val listId = insertList(list)
        return insert(task.copy(shoppingListId = listId))
    }

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(tasks: List<Task>)

    @Update
    suspend fun update(task: Task)


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
