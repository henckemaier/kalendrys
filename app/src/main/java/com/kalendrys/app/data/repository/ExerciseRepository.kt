package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.ExerciseItemDao
import com.kalendrys.app.data.local.entity.ExerciseItem
import kotlinx.coroutines.flow.Flow

/**
 * Lista de exercícios de uma rotina do tipo `EXERCICIO`. Fina sobre o [ExerciseItemDao]: cada
 * edição persiste na hora (a UI reflete via Flow), pela fila do editor da rotina (v1.9.0). Espelha o [ShoppingRepository]; sem total (isso era do módulo de compras).
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

    /**
     * Edita nome/séries/reps/carga/descanso do exercício [item] (pelo id), preservando rotina, posição e
     * criação como estão no BANCO: o [item] da tela pode estar velho, logo depois de um mover (NB-E3-A).
     */
    suspend fun update(item: ExerciseItem, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) =
        dao.updateFields(
            id = item.id,
            name = name.trim(),
            sets = sets.coerceAtLeast(1),
            reps = reps.coerceAtLeast(1),
            load = load?.trim()?.takeIf { it.isNotEmpty() },
            restSeconds = restSeconds?.takeIf { it >= 0 },
        )

    suspend fun remove(item: ExerciseItem) = dao.delete(item)

    /**
     * Reordena manualmente: troca a posição do exercício [item] (pelo id) com a do vizinho acima
     * ([up] = true) ou abaixo, RELENDO a lista atual dentro de uma transação: dois "subir" rápidos com
     * o item velho da tela não repetem posições (NB-E3-A). Nos extremos é um no-op.
     */
    suspend fun move(item: ExerciseItem, up: Boolean) = dao.moveById(item.routineId, item.id, up)

    /**
     * Restaura a lista da rotina exatamente como [snapshot] — usado pelo "Reverter alterações" do
     * editor (v1.9.0) para desfazer as edições de exercício (add/editar/remover/reordenar) da sessão. Troca atômica; o [snapshot] traz os itens como estavam ao abrir o editor (ids/ordem).
     */
    suspend fun replaceItems(routineId: Long, snapshot: List<ExerciseItem>) =
        dao.replaceForRoutine(routineId, snapshot)
}
