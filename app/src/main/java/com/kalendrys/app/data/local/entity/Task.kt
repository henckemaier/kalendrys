package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kalendrys.app.domain.ReminderRepeat

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Epoch millis do lembrete; null = sem lembrete. Em lembretes recorrentes é a primeira
     * ocorrência (a âncora) — as próximas saem de [com.kalendrys.app.domain.ReminderRecurrence].
     */
    val reminderAt: Long? = null,
    val reminderRepeat: ReminderRepeat = ReminderRepeat.NONE,
)
