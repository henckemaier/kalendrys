package com.kalendrys.app.ui.taskdetail

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat

/** Cópia editável de uma tarefa na tela de detalhe; só vai para o banco ao salvar. */
data class TaskDraft(
    val title: String,
    val notes: String,
    val reminderAt: Long?,
    val reminderRepeat: ReminderRepeat = ReminderRepeat.NONE,
) {
    fun differsFrom(task: Task): Boolean =
        title.trim() != task.title ||
            notes.trim() != task.notes ||
            reminderAt != task.reminderAt ||
            reminderRepeat != task.reminderRepeat

    /** O lembrete ficou como estava salvo (mesmo horário e mesma repetição). */
    fun keepsReminderOf(task: Task): Boolean =
        reminderAt == task.reminderAt && reminderRepeat == task.reminderRepeat

    /**
     * Um lembrete único novo precisa estar no futuro. O que já estava salvo pode ter passado
     * (já tocou) e continua aceito, para não bloquear a edição do título ou das observações;
     * um recorrente sempre tem uma próxima ocorrência.
     */
    fun canSaveOver(task: Task, now: Long): Boolean =
        differsFrom(task) &&
            title.isNotBlank() &&
            (
                reminderAt == null ||
                    reminderRepeat != ReminderRepeat.NONE ||
                    keepsReminderOf(task) ||
                    reminderAt > now
                )

    fun applyTo(task: Task): Task =
        task.copy(
            title = title.trim(),
            notes = notes.trim(),
            reminderAt = reminderAt,
            reminderRepeat = if (reminderAt == null) ReminderRepeat.NONE else reminderRepeat,
        )

    companion object {
        fun from(task: Task) = TaskDraft(task.title, task.notes, task.reminderAt, task.reminderRepeat)
    }
}
