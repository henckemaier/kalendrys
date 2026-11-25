package com.kalendrys.app.ui.routineeditor

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineDraftTest {

    private val routine = Routine(
        id = 7,
        title = "Treino",
        icon = RoutineIcon.WORKOUT.name,
        color = RoutineColor.GREEN.name,
        startMinute = 17 * 60 + 20,
        durationMinutes = 15,
        daysOfWeek = RoutineDays.WEEKDAYS,
        createdAt = 1_000L,
    )

    @Test
    fun `precisa de titulo e de ao menos um dia`() {
        val draft = RoutineDraft.from(routine)
        assertTrue(draft.isValid)
        assertFalse(draft.copy(title = "  ").isValid)
        assertFalse(draft.copy(daysOfWeek = 0).isValid)
    }

    @Test
    fun `sem mudancas nao difere e espacos nas pontas nao contam`() {
        val draft = RoutineDraft.from(routine)
        assertFalse(draft.differsFrom(routine))
        assertFalse(draft.copy(title = " Treino ").differsFrom(routine))
        assertTrue(draft.copy(color = RoutineColor.PINK).differsFrom(routine))
    }

    @Test
    fun `aplicar preserva id criacao e arquivamento`() {
        val updated = RoutineDraft.from(routine).copy(title = "Corrida", icon = RoutineIcon.RUN).applyTo(routine)
        assertEquals(routine.copy(title = "Corrida", icon = RoutineIcon.RUN.name), updated)
    }

    @Test
    fun `termina as calcula a duracao inclusive passando da meia-noite`() {
        val draft = RoutineDraft(startMinute = 23 * 60)
        assertEquals(90, draft.withEnd(30).durationMinutes) // 23:00 → 00:30
        assertTrue(draft.withEnd(30).endsNextDay)
        assertEquals(45, draft.withEnd(23 * 60 + 45).durationMinutes)
        assertEquals(0, draft.withEnd(23 * 60).durationMinutes) // mesmo horário = sem duração
        assertEquals(30, draft.withEnd(30).endMinuteOfDay)
    }

    @Test
    fun `nova rotina sai do rascunho com titulo aparado`() {
        val created = RoutineDraft(title = " Leitura ", startMinute = 21 * 60).toNewRoutine(now = 5L)
        assertEquals("Leitura", created.title)
        assertEquals(0L, created.id)
        assertEquals(5L, created.createdAt)
        assertEquals(RoutineDraft.DEFAULT_DURATION_MINUTES, created.durationMinutes)
    }

    @Test
    fun `recorrencia rica vai e volta entre rascunho e rotina`() {
        val rich = routine.copy(weekInterval = 2, startEpochDay = 20_000, endEpochDay = 20_100)
        val draft = RoutineDraft.from(rich)
        assertEquals(2, draft.weekInterval)
        assertEquals(20_000L, draft.startEpochDay)
        assertEquals(20_100L, draft.endEpochDay)
        assertEquals(rich, draft.applyTo(rich))
    }

    @Test
    fun `intervalo de semanas fica preso entre 1 e o maximo ao aplicar`() {
        val base = RoutineDraft.from(routine)
        assertEquals(1, base.copy(weekInterval = 0).applyTo(routine).weekInterval)
        assertEquals(RoutineDraft.MAX_WEEK_INTERVAL, base.copy(weekInterval = 999).applyTo(routine).weekInterval)
    }

    @Test
    fun `data final antes da inicial invalida o rascunho`() {
        val draft = RoutineDraft.from(routine)
        assertTrue(draft.copy(startEpochDay = 20_000, endEpochDay = 20_000).datesValid)
        assertTrue(draft.copy(startEpochDay = 20_000, endEpochDay = 20_010).datesValid)
        assertFalse(draft.copy(startEpochDay = 20_010, endEpochDay = 20_000).isValid)
        // Só uma das pontas definida nunca é inválida.
        assertTrue(draft.copy(startEpochDay = 20_010, endEpochDay = null).datesValid)
        assertTrue(draft.copy(startEpochDay = null, endEpochDay = 20_000).datesValid)
    }
}
