package com.kalendrys.app.domain

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rascunho do painel de nova tarefa (v1.7.1): regra da data sugerida e da lista excluída. */
class NewTaskDraftTest {

    private val today = LocalDate.of(2026, 11, 16)
    private val tapped = LocalDate.of(2026, 11, 20)

    @Test
    fun `rascunho novo comeca na data sugerida, vazio e sem poder salvar`() {
        val draft = NewTaskDraft.startingOn(today)
        assertEquals(today, draft.timing.date)
        assertFalse(draft.dateEdited)
        assertFalse(draft.canSave)
        assertFalse(draft.copy(title = "   ").canSave)
        assertTrue(draft.copy(title = "Pagar luz").canSave)
    }

    @Test
    fun `sem data escolhida pelo usuario, abrir de outro lugar troca a data e guarda o resto`() {
        val draft = NewTaskDraft.startingOn(today)
            .copy(title = "Pagar luz", notes = "conta de novembro")
            .withTiming(TaskTiming(date = today, time = LocalTime.of(9, 0)))

        val reopened = draft.openedOn(tapped)

        assertEquals(tapped, reopened.timing.date)
        assertEquals(LocalTime.of(9, 0), reopened.timing.time)
        assertEquals("Pagar luz", reopened.title)
        assertEquals("conta de novembro", reopened.notes)
    }

    @Test
    fun `data escolhida pelo usuario vale sobre o dia tocado`() {
        val chosen = LocalDate.of(2026, 12, 1)
        val draft = NewTaskDraft.startingOn(today).withTiming(TaskTiming(date = chosen))
        assertTrue(draft.dateEdited)
        assertSame(draft, draft.openedOn(tapped))
        assertEquals(chosen, draft.openedOn(tapped).timing.date)
    }

    @Test
    fun `tirar a data tambem e escolha do usuario`() {
        val draft = NewTaskDraft.startingOn(today).withTiming(TaskTiming(date = null))
        assertTrue(draft.dateEdited)
        assertNull(draft.openedOn(tapped).timing.date)
    }

    @Test
    fun `rascunho comeca sem lista e Adicionar lista so marca`() {
        // v1.10.0 (ADR-0002 16.10.4): sem lista existente; a lista nasce ao salvar, com o nome da tarefa.
        assertFalse(NewTaskDraft.startingOn(null).withList)
        assertTrue(NewTaskDraft(title = "Feira").copy(withList = true).withList)
    }
}
