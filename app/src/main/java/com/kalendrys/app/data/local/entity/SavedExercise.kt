package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Exercício salvo para reutilizar (v1.10.0, ADR-0002 16.10.5): UM exercício (nome, séries, repetições,
 * carga, descanso), fora de qualquer rotina. Aplicar um salvo numa rotina COPIA os campos para um
 * [ExerciseItem] novo; não há vínculo vivo entre os dois. Mesmos campos e regras do [ExerciseItem].
 */
@Entity(tableName = "saved_exercise")
data class SavedExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sets: Int,
    val reps: Int,
    /** Carga em texto livre (ex.: "20kg", "corporal"), opcional. */
    val load: String? = null,
    /** Descanso em segundos, opcional. */
    val restSeconds: Int? = null,
    val createdAt: Long,
)
