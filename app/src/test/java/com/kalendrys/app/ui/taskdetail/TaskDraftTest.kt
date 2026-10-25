package com.kalendrys.app.ui.taskdetail

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDraftTest {

    private val now = 1_000_000L
    private val task = Task(id = 7, title = "Revisar relatório", notes = "Seção 3", reminderAt = now - 60_000)

    @Test
    fun `rascunho recem criado nao tem alteracoes`() {
        val draft = TaskDraft.from(task)
        assertFalse(draft.differsFrom(task))
        assertFalse(draft.canSaveOver(task, now))
    }

    @Test
    fun `espacos nas pontas nao contam como alteracao`() {
        val draft = TaskDraft.from(task).copy(title = "  Revisar relatório ")
        assertFalse(draft.differsFrom(task))
    }

    @Test
    fun `titulo em branco nao pode ser salvo`() {
        val draft = TaskDraft.from(task).copy(title = "   ")
        assertTrue(draft.differsFrom(task))
        assertFalse(draft.canSaveOver(task, now))
    }

    @Test
    fun `lembrete salvo que ja passou nao impede editar o titulo`() {
        val draft = TaskDraft.from(task).copy(title = "Revisar relatório final")
        assertTrue(draft.canSaveOver(task, now))
    }

    @Test
    fun `novo lembrete no passado nao pode ser salvo`() {
        val draft = TaskDraft.from(task).copy(reminderAt = now - 1)
        assertFalse(draft.canSaveOver(task, now))
    }

    @Test
    fun `novo lembrete no futuro ou remocao do lembrete podem ser salvos`() {
        assertTrue(TaskDraft.from(task).copy(reminderAt = now + 60_000).canSaveOver(task, now))
        assertTrue(TaskDraft.from(task).copy(reminderAt = null).canSaveOver(task, now))
    }

    @Test
    fun `lembrete recorrente com inicio no passado pode ser salvo`() {
        val draft = TaskDraft.from(task).copy(reminderAt = now - 86_400_000, reminderRepeat = ReminderRepeat.DAILY)
        assertTrue(draft.canSaveOver(task, now))
    }

    @Test
    fun `deixar de repetir um lembrete que ja passou exige novo horario`() {
        val recurring = task.copy(reminderRepeat = ReminderRepeat.WEEKLY)
        val draft = TaskDraft.from(recurring).copy(reminderRepeat = ReminderRepeat.NONE)
        assertFalse(draft.canSaveOver(recurring, now))
    }

    @Test
    fun `remover o lembrete zera a repeticao`() {
        val recurring = task.copy(reminderRepeat = ReminderRepeat.DAILY)
        val updated = TaskDraft.from(recurring).copy(reminderAt = null).applyTo(recurring)
        assertEquals(null, updated.reminderAt)
        assertEquals(ReminderRepeat.NONE, updated.reminderRepeat)
    }

    @Test
    fun `aplicar o rascunho apara os textos e preserva id e data de criacao`() {
        val updated = TaskDraft("  Novo título ", " nota ", null).applyTo(task)
        assertEquals(task.copy(title = "Novo título", notes = "nota", reminderAt = null), updated)
    }
}
