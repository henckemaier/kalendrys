package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineSnoozeTest {

    @Test
    fun `adia pelos minutos padrao`() {
        val now = 1_000_000L
        assertEquals(now + 10 * 60_000L, RoutineSnooze.triggerAt(now, RoutineSnooze.DEFAULT_MINUTES))
    }

    @Test
    fun `padrao sao 10 minutos`() {
        assertEquals(10, RoutineSnooze.DEFAULT_MINUTES)
        assertEquals(10 * 60_000L, RoutineSnooze.triggerAt(now = 0L))
    }

    @Test
    fun `minutos personalizados`() {
        assertEquals(5 * 60_000L, RoutineSnooze.triggerAt(0L, 5))
        assertEquals(30 * 60_000L, RoutineSnooze.triggerAt(0L, 30))
    }

    @Test
    fun `minutos nao positivos caem para 1 minuto no futuro`() {
        assertEquals(60_000L, RoutineSnooze.triggerAt(0L, 0))
        assertEquals(60_000L, RoutineSnooze.triggerAt(0L, -5))
    }

    @Test
    fun `resultado fica sempre no futuro em relacao a now`() {
        val now = 123_456_789L
        assertTrue(RoutineSnooze.triggerAt(now, 10) > now)
        assertTrue(RoutineSnooze.triggerAt(now, 0) > now)
    }
}
