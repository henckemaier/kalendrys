package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.kalendrys.app.data.local.entity.ExerciseItem
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

    /**
     * Rotina nova com os exercícios dela (criação no editor e Duplicar, v1.10.0 16.10.5): grava a
     * rotina e, com o id dela, os [exercises] (cada um com `routineId` = 0 até aqui) numa transação
     * só. Falhar no meio não deixa rotina sem os exercícios que a tela mostrava. Devolve o id da rotina.
     */
    @Transaction
    suspend fun insertRoutineWithExercises(routine: Routine, exercises: List<ExerciseItem>): Long {
        val routineId = insert(routine)
        if (exercises.isNotEmpty()) insertExercises(exercises.map { it.copy(id = 0, routineId = routineId) })
        return routineId
    }

    /** Grava os exercícios SÓ dentro de [insertRoutineWithExercises] (USO RESTRITO). */
    @Insert
    suspend fun insertExercises(items: List<ExerciseItem>)

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

    /** Tipo ([com.kalendrys.app.domain.RoutineKind.name]); os exercícios ficam como estão (16.10.5). */
    @Query("UPDATE routines SET kind = :kind WHERE id = :id")
    suspend fun updateKind(id: Long, kind: String)

    @Query("UPDATE routines SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: String)

    @Query("UPDATE routines SET icon = :icon WHERE id = :id")
    suspend fun updateIcon(id: Long, icon: String)

    @Query("UPDATE routines SET pausedAt = :pausedAt WHERE id = :id")
    suspend fun updatePausedAt(id: Long, pausedAt: Long?)

    @Query("UPDATE routines SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun updateArchivedAt(id: Long, archivedAt: Long?)

    /** Apaga todas as rotinas (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM routines")
    suspend fun clear()
}
