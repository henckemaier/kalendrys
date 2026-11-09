package com.kalendrys.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat

/**
 * Tarefa. Sem repetição, ela é feita uma vez: concluída, sai das pendentes de vez. Com
 * repetição, cada dia em que ela acontece tem a própria conclusão.
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Dia da tarefa (LocalDate.toEpochDay); null = sem data. Numa tarefa que se repete, é o primeiro dia. */
    @ColumnInfo(defaultValue = "NULL")
    val dueDate: Long? = null,
    /**
     * Epoch millis do lembrete; null = sem lembrete. Fica sempre no dia [dueDate] (o horário
     * escolhido nesse dia) e, numa tarefa que se repete, é a âncora das próximas ocorrências
     * ([com.kalendrys.app.domain.ReminderRecurrence]).
     */
    val reminderAt: Long? = null,
    /** Como a tarefa se repete a partir de [dueDate] (o nome da coluna é de quando só o lembrete repetia). */
    @ColumnInfo(name = "reminderRepeat")
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    /**
     * Recorrência [ReminderRepeat.CUSTOM] "a cada N": N (intervalo). Ignorado nos outros tipos e
     * quando [byWeekdays] != 0 (modo multi-dia tem prioridade). Default 1.
     */
    @ColumnInfo(defaultValue = "1")
    val intervalEvery: Int = 1,
    /** Unidade do intervalo CUSTOM "a cada N"; null fora do modo intervalo. */
    @ColumnInfo(defaultValue = "NULL")
    val intervalUnit: RecurrenceUnit? = null,
    /**
     * Recorrência [ReminderRepeat.CUSTOM] por dias-da-semana: bitmask (SEG=bit0 … DOM=bit6);
     * 0 = modo intervalo. Default 0.
     */
    @ColumnInfo(defaultValue = "0")
    val byWeekdays: Int = 0,
    /** Local livre (endereço ou nome do lugar); vazio = sem local. */
    @ColumnInfo(defaultValue = "")
    val location: String = "",
)
