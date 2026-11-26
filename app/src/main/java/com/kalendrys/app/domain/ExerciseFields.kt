package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ExerciseItem

/**
 * Os campos de UM exercício, como o diálogo do editor os entrega: nome, séries, repetições, carga
 * (texto livre) e descanso em segundos. Sem rotina, posição nem criação: é o que o rascunho da rotina
 * nova guarda (v1.10.0, ADR-0002 16.10.5) e o que um exercício salvo copia.
 *
 * [normalized] é a regra única de gravação (a mesma de sempre do
 * [com.kalendrys.app.data.repository.ExerciseRepository]): nome aparado, séries e repetições no
 * mínimo 1, carga em branco vira sem carga e descanso negativo vira sem descanso. Lógica pura.
 */
data class ExerciseFields(
    val name: String,
    val sets: Int,
    val reps: Int,
    val load: String? = null,
    val restSeconds: Int? = null,
) {
    /** Sem nome não grava (o diálogo nem libera o confirmar). */
    val isValid: Boolean get() = name.isNotBlank()

    fun normalized(): ExerciseFields = ExerciseFields(
        name = name.trim(),
        sets = sets.coerceAtLeast(1),
        reps = reps.coerceAtLeast(1),
        load = load?.trim()?.takeIf { it.isNotEmpty() },
        restSeconds = restSeconds?.takeIf { it >= 0 },
    )

    /** O exercício da rotina [routineId] na posição [position], já normalizado. */
    fun toItem(routineId: Long, position: Int, createdAt: Long): ExerciseItem = normalized().let {
        ExerciseItem(
            routineId = routineId,
            name = it.name,
            sets = it.sets,
            reps = it.reps,
            load = it.load,
            restSeconds = it.restSeconds,
            position = position,
            createdAt = createdAt,
        )
    }

    companion object {
        fun of(item: ExerciseItem): ExerciseFields =
            ExerciseFields(name = item.name, sets = item.sets, reps = item.reps, load = item.load, restSeconds = item.restSeconds)
    }
}
