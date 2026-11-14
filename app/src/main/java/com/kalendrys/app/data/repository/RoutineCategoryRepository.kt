package com.kalendrys.app.data.repository

import androidx.room.withTransaction
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.domain.RoutineCategoryKind
import kotlinx.coroutines.flow.Flow

/**
 * Categorias de rotina. O [RoutineCategoryKind] decide o módulo de item da categoria: [SIMPLE]
 * (sem módulo) ou EXERCICIO (lista de exercícios). [COMPRAS] continua existindo (é persistido e está
 * em backups), mas desde a v1.5.0 é só um rótulo: a lista de compras é anexada a qualquer rotina
 * (ADR-0001, P6). O kind é gravado como a chave do enum
 * ([RoutineCategoryKind.name]), lido de volta por [RoutineCategoryKind.fromKey] — mesmo contrato de
 * [com.kalendrys.app.domain.RoutineColor]/[com.kalendrys.app.domain.RoutineIcon], sem mexer no schema
 * (a coluna `kind` existe desde o v7).
 */
class RoutineCategoryRepository(private val database: AppDatabase) {

    private val categoryDao = database.routineCategoryDao()
    private val routineDao = database.routineDao()

    val categories: Flow<List<RoutineCategory>> = categoryDao.observeAll()

    suspend fun create(name: String, color: String, kind: RoutineCategoryKind = RoutineCategoryKind.SIMPLE): Long =
        categoryDao.insert(
            RoutineCategory(name = name.trim(), color = color, kind = kind.name),
        )

    suspend fun update(category: RoutineCategory, name: String, color: String, kind: RoutineCategoryKind) =
        categoryDao.update(category.copy(name = name.trim(), color = color, kind = kind.name))

    /**
     * Exclui a categoria e desvincula as rotinas que a usavam (categoryId = null), num só passo
     * atômico: sem a transação, uma rotina criada entre o UPDATE e o DELETE ficaria órfã.
     */
    suspend fun delete(category: RoutineCategory) = database.withTransaction {
        routineDao.clearCategory(category.id)
        categoryDao.delete(category)
    }
}
