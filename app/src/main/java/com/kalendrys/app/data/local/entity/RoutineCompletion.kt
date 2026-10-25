package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Um bloco de rotina feito em um dia. [epochDay] é o dia da ocorrência (pode ser marcado depois,
 * ao corrigir um dia passado); o índice único impede marcar a mesma ocorrência duas vezes.
 * Sem foreign key, como o [CompletionLog]: o histórico sobrevive à exclusão da rotina.
 */
@Entity(
    tableName = "routine_completion",
    indices = [Index(value = ["routineId", "epochDay"], unique = true), Index("epochDay")],
)
data class RoutineCompletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val epochDay: Long,
    val completedAt: Long = System.currentTimeMillis(),
)
