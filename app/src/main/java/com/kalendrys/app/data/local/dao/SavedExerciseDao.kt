package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kalendrys.app.data.local.entity.SavedExercise
import kotlinx.coroutines.flow.Flow

/** Exercícios salvos (v1.10.0, ADR-0002 16.10.5). */
@Dao
interface SavedExerciseDao {

    /** Os salvos em ordem de nome (o painel "Dos salvos"); reativo. */
    @Query("SELECT * FROM saved_exercise ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SavedExercise>>

    /** Leitura única (backup). */
    @Query("SELECT * FROM saved_exercise ORDER BY id")
    suspend fun all(): List<SavedExercise>

    @Query("SELECT * FROM saved_exercise WHERE id = :id")
    suspend fun byId(id: Long): SavedExercise?

    @Insert
    suspend fun insert(exercise: SavedExercise): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(exercises: List<SavedExercise>)

    /** Edita os campos de um salvo; id e criação ficam. */
    @Query(
        "UPDATE saved_exercise SET name = :name, sets = :sets, reps = :reps, load = :load, " +
            "restSeconds = :restSeconds WHERE id = :id",
    )
    suspend fun updateFields(id: Long, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?)

    @Query("DELETE FROM saved_exercise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM saved_exercise")
    suspend fun count(): Int

    /** Restauro de backup: limpa antes de reinserir. */
    @Query("DELETE FROM saved_exercise")
    suspend fun clear()
}
