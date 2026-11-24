package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.kalendrys.app.data.local.entity.ExerciseItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseItemDao {

    /** Os exercícios de uma rotina na ordem manual da lista; reativo para a UI refletir edições na hora. */
    @Query("SELECT * FROM exercise_item WHERE routineId = :routineId ORDER BY position, id")
    fun observeByRoutine(routineId: Long): Flow<List<ExerciseItem>>

    /** A lista de uma rotina, leitura única (sem Flow). */
    @Query("SELECT * FROM exercise_item WHERE routineId = :routineId ORDER BY position, id")
    suspend fun byRoutine(routineId: Long): List<ExerciseItem>

    @Insert
    suspend fun insert(item: ExerciseItem): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(items: List<ExerciseItem>)

    // --- Salvar na hora (v1.9.0, NB-E3-A do Lince): gravações por id, só das colunas que mudam. ----
    // O item que a tela tem pode estar velho (outra mudança já passou pela fila): nada aqui regrava a
    // linha inteira a partir dele.

    /** Edita nome, séries, repetições, carga e descanso; id, rotina, posição e criação ficam. */
    @Query(
        "UPDATE exercise_item SET name = :name, sets = :sets, reps = :reps, load = :load, " +
            "restSeconds = :restSeconds WHERE id = :id",
    )
    suspend fun updateFields(id: Long, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?)

    @Query("UPDATE exercise_item SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    /**
     * Reordena: lê a lista ATUAL da rotina do item [itemId] (na ordem da tela, position e id), troca o
     * item de lugar com o vizinho acima ([up]) ou abaixo e regrava a posição pelo ÍNDICE de todo item
     * que estiver fora dele, numa transação. Em lista sã (sem buracos) são as duas gravações de uma troca;
     * em lista com posições repetidas (o defeito do mover da v1.8.0) ela é consertada, em vez de o
     * item ficar preso trocando dois valores iguais. Nos extremos, ou com o item já excluído, nada muda.
     */
    @Transaction
    suspend fun moveById(routineId: Long, itemId: Long, up: Boolean) {
        val items = byRoutine(routineId).toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in items.indices) return
        items[index] = items[target].also { items[target] = items[index] }
        items.forEachIndexed { position, item ->
            if (item.position != position) updatePosition(item.id, position)
        }
    }

    @Delete
    suspend fun delete(item: ExerciseItem)

    /** Apaga os exercícios de UMA rotina (restauro do snapshot ao descartar o editor). */
    @Query("DELETE FROM exercise_item WHERE routineId = :routineId")
    suspend fun deleteByRoutine(routineId: Long)

    /**
     * Restaura a lista de uma rotina exatamente como [items] (descartar edições de item no editor):
     * limpa a rotina e reinsere o snapshot preservando ids/position/createdAt via [insertAll].
     * Atômico, para a lista nunca ficar meio-trocada.
     */
    @Transaction
    suspend fun replaceForRoutine(routineId: Long, items: List<ExerciseItem>) {
        deleteByRoutine(routineId)
        insertAll(items)
    }

    /** Todos os itens, leitura única para a exportação do backup. */
    @Query("SELECT * FROM exercise_item ORDER BY routineId, position, id")
    suspend fun all(): List<ExerciseItem>

    /** Apaga todos os itens (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM exercise_item")
    suspend fun clear()
}
