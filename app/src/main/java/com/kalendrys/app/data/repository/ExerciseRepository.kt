package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.ExerciseItemDao
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.domain.ExerciseFields
import kotlinx.coroutines.flow.Flow

/**
 * Lista de exercícios de uma rotina do tipo `EXERCICIO`. Fina sobre o [ExerciseItemDao]: cada
 * edição persiste na hora (a UI reflete via Flow), pela fila do editor da rotina (v1.9.0). Espelha o [ShoppingRepository]; sem total (isso era do módulo de compras).
 */
class ExerciseRepository(
    private val dao: ExerciseItemDao,
    /** O relógio do app (v1.10.0, 16.3): o carimbo de criação dos exercícios. */
    private val clock: AppClock,
) {

    fun observeItems(routineId: Long): Flow<List<ExerciseItem>> = dao.observeByRoutine(routineId)

    /** Os exercícios da rotina agora, na ordem (o Duplicar copia daqui). */
    suspend fun itemsOf(routineId: Long): List<ExerciseItem> = dao.byRoutine(routineId)

    /**
     * Novo exercício no fim da lista (position após o maior atual). [sets]/[reps] no mínimo 1;
     * [load] (texto livre) e [restSeconds] opcionais — em branco viram `null` (sem carga/descanso).
     * A regra é a do [ExerciseFields.normalized], a mesma da criação da rotina com exercícios.
     */
    suspend fun add(routineId: Long, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        val nextPosition = (dao.byRoutine(routineId).maxOfOrNull { it.position } ?: -1) + 1
        dao.insert(ExerciseFields(name, sets, reps, load, restSeconds).toItem(routineId, nextPosition, clock.nowMillis()))
    }

    /**
     * Edita nome/séries/reps/carga/descanso do exercício [item] (pelo id), preservando rotina, posição e
     * criação como estão no BANCO: o [item] da tela pode estar velho, logo depois de um mover (NB-E3-A).
     */
    suspend fun update(item: ExerciseItem, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        val fields = ExerciseFields(name, sets, reps, load, restSeconds).normalized()
        dao.updateFields(
            id = item.id,
            name = fields.name,
            sets = fields.sets,
            reps = fields.reps,
            load = fields.load,
            restSeconds = fields.restSeconds,
        )
    }

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
