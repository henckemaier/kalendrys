package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.dao.SavedExerciseDao
import com.kalendrys.app.data.local.entity.SavedExercise
import com.kalendrys.app.domain.ExerciseFields
import kotlinx.coroutines.flow.Flow

/**
 * Exercícios salvos para reutilizar (v1.10.0, ADR-0002 16.10.5). Um salvo é UM exercício, fora de
 * qualquer rotina; aplicar um salvo numa rotina COPIA os campos (quem copia é o editor, pelo caminho
 * de sempre do exercício), sem vínculo vivo: editar ou excluir o salvo não mexe em rotina nenhuma.
 * Mesmas regras de gravação do exercício da rotina ([ExerciseFields.normalized]); sem nome não grava.
 */
class SavedExerciseRepository(
    private val dao: SavedExerciseDao,
    /** O relógio do app (v1.10.0, 16.3): o carimbo de criação. */
    private val clock: AppClock,
) {

    /** Os salvos, por nome (o painel "Dos salvos"). */
    val saved: Flow<List<SavedExercise>> = dao.observeAll()

    /** Salva uma cópia de [fields]; devolve o id, ou null se sem nome. Nome repetido é permitido. */
    suspend fun save(fields: ExerciseFields): Long? {
        if (!fields.isValid) return null
        val f = fields.normalized()
        return dao.insert(
            SavedExercise(
                name = f.name,
                sets = f.sets,
                reps = f.reps,
                load = f.load,
                restSeconds = f.restSeconds,
                createdAt = clock.nowMillis(),
            ),
        )
    }

    /** Edita o salvo [id] (criação fica); sem nome não grava. Rotinas que já o copiaram não mudam. */
    suspend fun update(id: Long, fields: ExerciseFields) {
        if (!fields.isValid) return
        val f = fields.normalized()
        dao.updateFields(id = id, name = f.name, sets = f.sets, reps = f.reps, load = f.load, restSeconds = f.restSeconds)
    }

    /** Exclui o salvo [id] (o painel confirma antes); as cópias nas rotinas ficam. */
    suspend fun delete(id: Long) = dao.delete(id)
}
