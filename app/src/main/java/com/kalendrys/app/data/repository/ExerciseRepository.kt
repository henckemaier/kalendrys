package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.ExerciseItemDao
import com.kalendrys.app.data.local.entity.ExerciseItem
import kotlinx.coroutines.flow.Flow

/**
 * Lista de exercícios de uma rotina do tipo `EXERCICIO`. Fina sobre o [ExerciseItemDao]: cada
 * edição persiste na hora (a UI reflete via Flow), independente do salvar/descartar do editor da
 * rotina. Espelha o [ShoppingRepository]; sem total (isso era do módulo de compras).
 */
class ExerciseRepository(private val dao: ExerciseItemDao) {

    fun observeItems(routineId: Long): Flow<List<ExerciseItem>> = dao.observeByRoutine(routineId)

    /**
     * Novo exercício no fim da lista (position após o maior atual). [sets]/[reps] no mínimo 1;
     * [load] (texto livre) e [restSeconds] opcionais — em branco viram `null` (sem carga/descanso).
     */
    suspend fun add(routineId: Long, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        val nextPosition = (dao.byRoutine(routineId).maxOfOrNull { it.position } ?: -1) + 1
        dao.insert(
            ExerciseItem(
                routineId = routineId,
                name = name.trim(),
                sets = sets.coerceAtLeast(1),
                reps = reps.coerceAtLeast(1),
                load = load?.trim()?.takeIf { it.isNotEmpty() },
                restSeconds = restSeconds?.takeIf { it >= 0 },
                position = nextPosition,
            ),
        )
    }

    /** Edita nome/séries/reps/carga/descanso preservando id, routineId, position e createdAt. */
    suspend fun update(item: ExerciseItem, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) =
        dao.update(
            item.copy(
                name = name.trim(),
                sets = sets.coerceAtLeast(1),
                reps = reps.coerceAtLeast(1),
                load = load?.trim()?.takeIf { it.isNotEmpty() },
                restSeconds = restSeconds?.takeIf { it >= 0 },
            ),
        )

    suspend fun remove(item: ExerciseItem) = dao.delete(item)

    /**
     * Reordena manualmente: troca a [ExerciseItem.position] do [item] com a do vizinho acima
     * ([up] = true) ou abaixo. Nos extremos (nada acima/abaixo) é um no-op. A ordenação por
     * (position, id) no DAO faz a lista refletir a troca.
     */
    suspend fun move(item: ExerciseItem, up: Boolean) {
        val items = dao.byRoutine(item.routineId)
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val targetIndex = if (up) index - 1 else index + 1
        val other = items.getOrNull(targetIndex) ?: return
        // Troca os valores de position entre os dois vizinhos (distintos pelo add = max+1).
        dao.update(item.copy(position = other.position))
        dao.update(other.copy(position = item.position))
    }
}
