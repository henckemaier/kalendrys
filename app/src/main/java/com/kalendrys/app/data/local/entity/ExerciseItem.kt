package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Um exercício de uma rotina do tipo `EXERCICIO` ([RoutineCategoryKind.EXERCICIO]).
 * [routineId] liga o exercício à rotina; [position] dá a ordem manual na lista. [sets] e [reps]
 * são um de cada por exercício; [load] é a carga em TEXTO LIVRE (ex.: "20kg", "corporal", "banda"),
 * opcional; [restSeconds] é só o descanso em segundos (nullable, sem timer — apenas o valor).
 *
 * Sem foreign key, como o [ShoppingItem]/[RoutineCompletion]/[RoutineOverride]: o exercício
 * sobrevive à exclusão da rotina e entra no backup. O índice por [routineId] serve às consultas
 * da lista por rotina.
 */
@Entity(
    tableName = "exercise_item",
    indices = [Index("routineId")],
)
data class ExerciseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val name: String,
    val sets: Int,
    val reps: Int,
    val load: String? = null,
    val restSeconds: Int? = null,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
