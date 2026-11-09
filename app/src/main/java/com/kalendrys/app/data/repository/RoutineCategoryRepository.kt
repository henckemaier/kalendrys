package com.kalendrys.app.data.repository

import androidx.room.withTransaction
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.domain.RoutineCategoryKind
import kotlinx.coroutines.flow.Flow

/**
 * Categorias de rotina. No v1.2.0 só o [RoutineCategoryKind.SIMPLE] é funcional (agrupar/filtrar);
 * EXERCICIO/COMPRAS ficam reservados no enum e fora da UI.
 */
class RoutineCategoryRepository(private val database: AppDatabase) {

    private val categoryDao = database.routineCategoryDao()
    private val routineDao = database.routineDao()

    val categories: Flow<List<RoutineCategory>> = categoryDao.observeAll()

    suspend fun create(name: String, color: String): Long =
        categoryDao.insert(
            RoutineCategory(name = name.trim(), color = color, kind = RoutineCategoryKind.SIMPLE.name),
        )

    suspend fun update(category: RoutineCategory, name: String, color: String) =
        categoryDao.update(category.copy(name = name.trim(), color = color))

    /**
     * Exclui a categoria e desvincula as rotinas que a usavam (categoryId = null), num só passo
     * atômico: sem a transação, uma rotina criada entre o UPDATE e o DELETE ficaria órfã.
     */
    suspend fun delete(category: RoutineCategory) = database.withTransaction {
        routineDao.clearCategory(category.id)
        categoryDao.delete(category)
    }
}
