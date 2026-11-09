package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.kalendrys.app.data.local.entity.RoutineCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineCategoryDao {

    @Query("SELECT * FROM routine_category ORDER BY name, id")
    suspend fun all(): List<RoutineCategory>

    /** As categorias em ordem, de forma reativa (para a UI e o filtro da timeline). */
    @Query("SELECT * FROM routine_category ORDER BY name, id")
    fun observeAll(): Flow<List<RoutineCategory>>

    @Insert
    suspend fun insert(category: RoutineCategory): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(categories: List<RoutineCategory>)

    @Update
    suspend fun update(category: RoutineCategory)

    @Delete
    suspend fun delete(category: RoutineCategory)

    /** Apaga todas as categorias (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM routine_category")
    suspend fun clear()
}
