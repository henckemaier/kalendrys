package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.RoutineCategoryDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.domain.RoutineCategoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Categorias de rotina. No v1.2.0 só o [RoutineCategoryKind.SIMPLE] é funcional (agrupar/filtrar);
 * EXERCICIO/COMPRAS ficam reservados no enum e fora da UI.
 *
 * Ao excluir uma categoria, as rotinas que a usavam ficam sem categoria (categoryId = null), para
 * não sobrar referência órfã — feito com o update já existente, sem nova query no DAO.
 */
class RoutineCategoryRepository(
    private val categoryDao: RoutineCategoryDao,
    private val routineDao: RoutineDao,
) {

    val categories: Flow<List<RoutineCategory>> = categoryDao.observeAll()

    suspend fun create(name: String, color: String): Long =
        categoryDao.insert(
            RoutineCategory(name = name.trim(), color = color, kind = RoutineCategoryKind.SIMPLE.name),
        )

    suspend fun update(category: RoutineCategory, name: String, color: String) =
        categoryDao.update(category.copy(name = name.trim(), color = color))

    suspend fun delete(category: RoutineCategory) {
        routineDao.observeAll().first()
            .filter { it.categoryId == category.id }
            .forEach { routineDao.update(it.copy(categoryId = null)) }
        categoryDao.delete(category)
    }
}
