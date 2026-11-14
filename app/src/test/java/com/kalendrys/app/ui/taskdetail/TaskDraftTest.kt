package com.kalendrys.app.ui.taskdetail

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDraftTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 10, 28)
    private val task = TaskTiming(day, LocalTime.of(9, 0)).applyTo(
        Task(id = 7, title = "Revisar relatório", notes = "Seção 3", location = "Escritório"),
        zone,
    )

    @Test
    fun `rascunho recem criado nao tem alteracoes`() {
        val draft = TaskDraft.from(task, zone)
        assertFalse(draft.differsFrom(task, zone))
        assertFalse(draft.canSaveOver(task, zone))
    }

    @Test
    fun `espacos nas pontas nao contam como alteracao`() {
        val draft = TaskDraft.from(task, zone).copy(title = "  Revisar relatório ", location = " Escritório ")
        assertFalse(draft.differsFrom(task, zone))
    }

    @Test
    fun `titulo em branco nao pode ser salvo`() {
        val draft = TaskDraft.from(task, zone).copy(title = "   ")
        assertTrue(draft.differsFrom(task, zone))
        assertFalse(draft.canSaveOver(task, zone))
    }

    @Test
    fun `mudar data local ou lembrete pode ser salvo`() {
        val draft = TaskDraft.from(task, zone)
        assertTrue(draft.copy(timing = draft.timing.copy(date = day.plusDays(1))).canSaveOver(task, zone))
        assertTrue(draft.copy(location = "Casa").canSaveOver(task, zone))
        assertTrue(draft.copy(timing = draft.timing.copy(time = null)).canSaveOver(task, zone))
    }

    @Test
    fun `lembrete fica no dia da tarefa e acompanha a data`() {
        val draft = TaskDraft.from(task, zone)
        val moved = draft.copy(timing = draft.timing.copy(date = day.plusDays(2))).applyTo(task, zone)
        assertEquals(day.plusDays(2).toEpochDay(), moved.dueDate)
        assertEquals(day.plusDays(2).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), moved.reminderAt)
    }

    @Test
    fun `sem data a tarefa perde lembrete e repeticao`() {
        val recurring = task.copy(repeat = ReminderRepeat.DAILY)
        val updated = TaskDraft.from(recurring, zone).copy(timing = TaskTiming(date = null)).applyTo(recurring, zone)
        assertNull(updated.dueDate)
        assertNull(updated.reminderAt)
        assertEquals(ReminderRepeat.NONE, updated.repeat)
    }

    @Test
    fun `repetir vale mesmo sem lembrete`() {
        val draft = TaskDraft.from(task, zone)
        val updated = draft.copy(timing = TaskTiming(day, time = null, repeat = ReminderRepeat.WEEKLY)).applyTo(task, zone)
        assertNull(updated.reminderAt)
        assertEquals(ReminderRepeat.WEEKLY, updated.repeat)
    }

    @Test
    fun `aplicar o rascunho apara os textos e preserva id e data de criacao`() {
        val updated = TaskDraft("  Novo título ", " nota ", TaskTiming(null), " Casa ").applyTo(task, zone)
        assertEquals(
            task.copy(title = "Novo título", notes = "nota", location = "Casa", dueDate = null, reminderAt = null),
            updated,
        )
    }

    @Test
    fun `lista de compras vai e volta e conta como alteracao`() {
        val linked = task.copy(shoppingListId = 42)
        val draft = TaskDraft.from(linked, zone)
        assertEquals(42L, draft.shoppingListId)
        assertFalse(draft.differsFrom(linked, zone))
        assertEquals(linked, draft.applyTo(linked, zone))
        // Anexar, trocar ou desvincular a lista é alteração do rascunho (P7) e pode ser salvo.
        assertTrue(TaskDraft.from(task, zone).copy(shoppingListId = 42).canSaveOver(task, zone))
        assertTrue(draft.copy(shoppingListId = 43).differsFrom(linked, zone))
        assertTrue(draft.copy(shoppingListId = null).canSaveOver(linked, zone))
        assertNull(draft.copy(shoppingListId = null).applyTo(linked, zone).shoppingListId)
    }

    @Test
    fun `lista nao mexe no lembrete`() {
        val draft = TaskDraft.from(task, zone).copy(shoppingListId = 42)
        assertTrue(draft.keepsReminderOf(task, zone))
        assertEquals(task.reminderAt, draft.applyTo(task, zone).reminderAt)
    }

    // --- Rebase: a tarefa salva mudou por fora com o detalhe aberto (v1.6.0) ---

    /** Como o logCompletion deixa uma tarefa única de data futura: data de hoje, sem lembrete. */
    private fun completedEarly(t: Task) = t.copy(dueDate = day.minusDays(1).toEpochDay(), reminderAt = null)

    @Test
    fun `rebase sem edicao assume o banco`() {
        val draft = TaskDraft.from(task, zone)
        val newBase = completedEarly(task)
        val rebased = draft.rebase(task, newBase, zone)
        assertEquals(TaskDraft.from(newBase, zone), rebased)
        assertFalse(rebased.differsFrom(newBase, zone))
    }

    @Test
    fun `edicao de outro campo fica e o resto assume o banco`() {
        val draft = TaskDraft.from(task, zone).copy(title = "Revisar relatório final")
        val newBase = completedEarly(task)
        val rebased = draft.rebase(task, newBase, zone)
        assertEquals("Revisar relatório final", rebased.title)
        assertEquals(TaskDraft.from(newBase, zone).timing, rebased.timing)
        assertEquals(task.notes, rebased.notes)
    }

    @Test
    fun `conflito no timing vence o usuario e o timing nao se mistura`() {
        val edited = TaskTiming(day.plusDays(3), LocalTime.of(18, 30), ReminderRepeat.WEEKLY)
        val draft = TaskDraft.from(task, zone).copy(timing = edited)
        val rebased = draft.rebase(task, completedEarly(task), zone)
        assertEquals(edited, rebased.timing)
    }

    @Test
    fun `vinculo de lista zerado por fora`() {
        val linked = task.copy(shoppingListId = 4)
        val draft = TaskDraft.from(linked, zone)
        val rebased = draft.rebase(linked, linked.copy(shoppingListId = null), zone)
        assertNull(rebased.shoppingListId)
    }

    @Test
    fun `vinculo trocado pelo usuario resiste a lista excluida por fora`() {
        val linked = task.copy(shoppingListId = 4)
        val draft = TaskDraft.from(linked, zone).copy(shoppingListId = 9)
        assertEquals(9L, draft.rebase(linked, linked.copy(shoppingListId = null), zone).shoppingListId)
    }
}
