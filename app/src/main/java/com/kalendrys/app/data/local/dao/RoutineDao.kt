package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.ShoppingList
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

    /**
     * Rotina nova com lista nova (criação no editor, v1.9.0 15.5): grava a lista e a rotina já
     * vinculada numa transação só e devolve o id da rotina. [list] vem de [ShoppingList.named].
     */
    @Transaction
    suspend fun insertRoutineWithNewList(list: ShoppingList, routine: Routine): Long {
        val listId = insertList(list)
        return insert(routine.copy(shoppingListId = listId))
    }

    /**
     * Grava a lista SÓ dentro de [insertRoutineWithNewList] e [linkNewList] (USO RESTRITO). Fora delas
     * a lista se cria pelo [com.kalendrys.app.data.repository.ShoppingRepository.createList].
     */
    @Insert
    suspend fun insertList(list: ShoppingList): Long

    // --- Salvar na hora (v1.9.0, ADR-0002 15.2) e estados de fora: cada gravação mexe SÓ nas suas ---
    // colunas. Quem chama é o RoutineRepository, sob o Mutex dele. Pausar, arquivar e editar não se
    // desfazem uns aos outros mesmo partindo de objetos lidos em momentos diferentes.

    @Query("UPDATE routines SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE routines SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("UPDATE routines SET startMinute = :startMinute WHERE id = :id")
    suspend fun updateStart(id: Long, startMinute: Int)

    @Query("UPDATE routines SET durationMinutes = :minutes WHERE id = :id")
    suspend fun updateDuration(id: Long, minutes: Int)

    @Query("UPDATE routines SET daysOfWeek = :mask WHERE id = :id")
    suspend fun updateDays(id: Long, mask: Int)

    @Query("UPDATE routines SET weekInterval = :weeks WHERE id = :id")
    suspend fun updateWeekInterval(id: Long, weeks: Int)

    /** Início e fim da vigência juntos: mover o início para depois do fim limpa o fim (5.3). */
    @Query("UPDATE routines SET startEpochDay = :startEpochDay, endEpochDay = :endEpochDay WHERE id = :id")
    suspend fun updateValidity(id: Long, startEpochDay: Long?, endEpochDay: Long?)

    @Query("UPDATE routines SET remind = :remind WHERE id = :id")
    suspend fun updateRemind(id: Long, remind: Boolean)

    @Query("UPDATE routines SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCategory(id: Long, categoryId: Long?)

    @Query("UPDATE routines SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: String)

    @Query("UPDATE routines SET icon = :icon WHERE id = :id")
    suspend fun updateIcon(id: Long, icon: String)

    @Query("UPDATE routines SET shoppingListId = :listId WHERE id = :id")
    suspend fun updateShoppingList(id: Long, listId: Long?)

    @Query("UPDATE routines SET pausedAt = :pausedAt WHERE id = :id")
    suspend fun updatePausedAt(id: Long, pausedAt: Long?)

    @Query("UPDATE routines SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun updateArchivedAt(id: Long, archivedAt: Long?)

    @Query("SELECT EXISTS(SELECT 1 FROM shopping_list WHERE id = :listId)")
    suspend fun listExists(listId: Long): Boolean

    /** Vincula a uma lista EXISTENTE, conferida na mesma transação; devolve se vinculou. */
    @Transaction
    suspend fun linkExistingList(routineId: Long, listId: Long): Boolean {
        if (!listExists(listId)) return false
        updateShoppingList(routineId, listId)
        return true
    }

    /** Cria a lista e vincula a rotina numa transação só; devolve o id da lista. */
    @Transaction
    suspend fun linkNewList(routineId: Long, list: ShoppingList): Long {
        val listId = insertList(list)
        updateShoppingList(routineId, listId)
        return listId
    }

    /** Desvincula as rotinas de uma categoria (ao excluí-la); rode dentro de uma transação. */
    @Query("UPDATE routines SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    /** Rotinas não arquivadas com lista anexada (etiqueta de dono na aba Listas e no cartão). */
    @Query("SELECT id, title, shoppingListId FROM routines WHERE shoppingListId IS NOT NULL AND archivedAt IS NULL ORDER BY title, id")
    fun observeShoppingListOwners(): Flow<List<ShoppingListOwnerRow>>

    /** Desvincula as rotinas de uma lista (ao excluí-la); rode dentro de uma transação. */
    @Query("UPDATE routines SET shoppingListId = NULL WHERE shoppingListId = :listId")
    suspend fun clearShoppingList(listId: Long)

    /** Apaga todas as rotinas (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM routines")
    suspend fun clear()
}
