package com.kalendrys.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
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
    /** Local livre (endereço ou nome do lugar); vazio = sem local. */
    @ColumnInfo(defaultValue = "")
    val location: String = "",
)
