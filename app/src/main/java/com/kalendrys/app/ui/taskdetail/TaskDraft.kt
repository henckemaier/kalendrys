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
    /**
     * Lista de compras anexada ([com.kalendrys.app.data.local.entity.ShoppingList.id]), ou null.
     * Faz parte do rascunho (ADR-0001, P7): grava com "Salvar", volta com "Descartar". Os itens da
     * lista NÃO são do rascunho; persistem na hora.
     */
    val shoppingListId: Long? = null,
) {
    fun applyTo(task: Task, zone: ZoneId): Task =
        timing.applyTo(
            task.copy(
                title = title.trim(),
                notes = notes.trim(),
                location = location.trim(),
                shoppingListId = shoppingListId,
            ),
            zone,
        )

    fun differsFrom(task: Task, zone: ZoneId): Boolean = applyTo(task, zone) != task

    /** O lembrete ficou como estava salvo (mesmo horário, mesma repetição e mesma config custom). */
    fun keepsReminderOf(task: Task, zone: ZoneId): Boolean =
        timing.reminderAt(zone) == task.reminderAt &&
            timing.effectiveRepeat == task.repeat &&
            timing.intervalEvery == task.intervalEvery &&
            timing.intervalUnit == task.intervalUnit &&
            timing.byWeekdays == task.byWeekdays

    /** Um lembrete que já passou não impede salvar: a tela só avisa que ele não vai tocar. */
    fun canSaveOver(task: Task, zone: ZoneId): Boolean = title.isNotBlank() && differsFrom(task, zone)

    /**
     * A tarefa salva mudou por fora enquanto o detalhe estava aberto (concluir tarefa única de data
     * futura muda data e lembrete; excluir a lista vinculada zera o vínculo). Para cada campo: se o
     * rascunho ainda tem o valor que veio de [oldBase] (o usuário não editou), assume o de [newBase];
     * se o usuário editou, a edição fica (no conflito, vence o usuário). O [timing] (data, hora,
     * repetição e config custom) é UM campo só: nunca mistura metade banco, metade rascunho.
     *
     * Ponte até a v1.8.0, que remove o rascunho; não generalizar para o editor de rotina.
     */
    fun rebase(oldBase: Task, newBase: Task, zone: ZoneId): TaskDraft {
        val from = from(oldBase, zone)
        val to = from(newBase, zone)
        return TaskDraft(
            title = if (title == from.title) to.title else title,
            notes = if (notes == from.notes) to.notes else notes,
            timing = if (timing == from.timing) to.timing else timing,
            location = if (location == from.location) to.location else location,
            shoppingListId = if (shoppingListId == from.shoppingListId) to.shoppingListId else shoppingListId,
        )
    }

    companion object {
        fun from(task: Task, zone: ZoneId) =
            TaskDraft(task.title, task.notes, TaskTiming.of(task, zone), task.location, task.shoppingListId)
    }
}
