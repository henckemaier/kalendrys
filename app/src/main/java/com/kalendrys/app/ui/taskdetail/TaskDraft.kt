package com.kalendrys.app.ui.taskdetail

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.TaskTiming
import java.time.ZoneId

/** Cópia editável de uma tarefa na tela de detalhe; só vai para o banco ao salvar. */
data class TaskDraft(
    val title: String,
    val notes: String,
    val timing: TaskTiming,
    val location: String = "",
) {
    fun applyTo(task: Task, zone: ZoneId): Task =
        timing.applyTo(task.copy(title = title.trim(), notes = notes.trim(), location = location.trim()), zone)

    fun differsFrom(task: Task, zone: ZoneId): Boolean = applyTo(task, zone) != task

    /** O lembrete ficou como estava salvo (mesmo horário e mesma repetição). */
    fun keepsReminderOf(task: Task, zone: ZoneId): Boolean =
        timing.reminderAt(zone) == task.reminderAt && timing.effectiveRepeat == task.repeat

    /** Um lembrete que já passou não impede salvar: a tela só avisa que ele não vai tocar. */
    fun canSaveOver(task: Task, zone: ZoneId): Boolean = title.isNotBlank() && differsFrom(task, zone)

    companion object {
        fun from(task: Task, zone: ZoneId) = TaskDraft(task.title, task.notes, TaskTiming.of(task, zone), task.location)
    }
}
