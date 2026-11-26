package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
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
     * Grava a lista SÓ dentro de [insertTaskWithNewList] e [linkNewList] (USO RESTRITO). Fora dela a lista se cria
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


    // --- Salvar na hora (v1.9.0, ADR-0002 15.2): cada mudança grava SÓ as colunas dela. ---------------
    // Quem chama é o TaskRepository.edit, sob a trava dele; nada aqui regrava a linha inteira, então
    // uma gravação de outro caminho (excluir a lista, concluir) entre a leitura e a escrita não é desfeita.

    @Query("UPDATE tasks SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE tasks SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("UPDATE tasks SET location = :location WHERE id = :id")
    suspend fun updateLocation(id: Long, location: String)

    /** Data, lembrete e repetição juntos: um campo só, nunca aplicado pela metade. */
    @Query(
        "UPDATE tasks SET dueDate = :dueDate, reminderAt = :reminderAt, reminderRepeat = :repeat, " +
            "intervalEvery = :intervalEvery, intervalUnit = :intervalUnit, byWeekdays = :byWeekdays WHERE id = :id",
    )
    suspend fun updateTiming(
        id: Long,
        dueDate: Long?,
        reminderAt: Long?,
        repeat: ReminderRepeat,
        intervalEvery: Int,
        intervalUnit: RecurrenceUnit?,
        byWeekdays: Int,
    )

    @Query("UPDATE tasks SET shoppingListId = :listId WHERE id = :id")
    suspend fun updateShoppingList(id: Long, listId: Long?)

    @Query("SELECT EXISTS(SELECT 1 FROM shopping_list WHERE id = :listId)")
    suspend fun listExists(listId: Long): Boolean

    /**
     * Cria a lista [list] (de [ShoppingList.named]) e vincula a tarefa a ela numa transação só (v1.9.0,
     * fecha o NB-E6-A): falhar o vínculo não deixa lista avulsa. Devolve o id da lista.
     */
    @Transaction
    suspend fun linkNewList(taskId: Long, list: ShoppingList): Long {
        val listId = insertList(list)
        updateShoppingList(taskId, listId)
        return listId
    }


    @Delete
    suspend fun delete(task: Task)

    // --- Excluir a tarefa com a lista (v1.10.0, ADR-0002 16.10.4): a lista é da tarefa. -------------

    @Query("SELECT * FROM shopping_list WHERE id = :listId")
    suspend fun listById(listId: Long): ShoppingList?

    @Query("SELECT * FROM shopping_item WHERE listId = :listId ORDER BY position, id")
    suspend fun itemsOf(listId: Long): List<ShoppingItem>

    @Query("DELETE FROM shopping_item WHERE listId = :listId")
    suspend fun deleteItemsOf(listId: Long)

    @Query("DELETE FROM shopping_list WHERE id = :listId")
    suspend fun deleteListById(listId: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** USO RESTRITO: só dentro de [restoreTaskWithList]. */
    @Insert
    suspend fun insertItems(items: List<ShoppingItem>)

    /**
     * Apaga a tarefa, a lista dela e os itens numa transação só e devolve as três como estavam (lidas
     * na MESMA transação), para o Desfazer devolver tudo com os mesmos ids. Lê a linha atual pelo id:
     * a tarefa que a tela tinha pode estar velha. Tarefa que não existe mais: nada, `null`.
     */
    @Transaction
    suspend fun deleteTaskWithList(taskId: Long): TaskWithList? {
        val task = byId(taskId) ?: return null
        val list = task.shoppingListId?.let { listById(it) }
        val items = list?.let { itemsOf(it.id) }.orEmpty()
        if (list != null) {
            deleteItemsOf(list.id)
            deleteListById(list.id)
        }
        deleteById(taskId)
        return TaskWithList(task, list, items)
    }

    /**
     * Devolve uma tarefa excluída com a lista e os itens, com os mesmos ids, numa transação só. A
     * lista só volta se ainda não existe (os ids não se repetem; é a defesa contra um Desfazer duplo).
     */
    @Transaction
    suspend fun restoreTaskWithList(deleted: TaskWithList) {
        val list = deleted.list
        if (list != null && !listExists(list.id)) {
            insertList(list)
            insertItems(deleted.items)
        }
        insert(deleted.task)
    }

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
