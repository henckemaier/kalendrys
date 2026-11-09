package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Exceção pontual de uma rotina num dia específico: pular a ocorrência ([type] = `SKIP`) ou
 * remarcá-la ([type] = `RESCHEDULE`, com [newStartMinute] e/ou [newEpochDay]). O índice único
 * (routineId, epochDay) garante no máximo uma exceção por rotina/dia.
 *
 * Sem foreign key, como o [RoutineCompletion]: a exceção não precisa cair junto se a rotina for
 * excluída. [type] fica como texto (SKIP/RESCHEDULE) — a LÓGICA de aplicação é das tasks seguintes
 * (T19/T22/T23); aqui é só o esquema de dados.
 */
@Entity(
    tableName = "routine_override",
    indices = [Index(value = ["routineId", "epochDay"], unique = true)],
)
data class RoutineOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val epochDay: Long,
    val type: String,
    val newStartMinute: Int? = null,
    val newEpochDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
