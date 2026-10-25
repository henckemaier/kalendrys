package com.kalendrys.app.domain

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineDaysTest {

    @Test
    fun `mascaras dos conjuntos comuns`() {
        assertTrue(RoutineDays.contains(RoutineDays.WEEKDAYS, DayOfWeek.FRIDAY))
        assertFalse(RoutineDays.contains(RoutineDays.WEEKDAYS, DayOfWeek.SATURDAY))
        assertTrue(RoutineDays.contains(RoutineDays.WEEKEND, DayOfWeek.SUNDAY))
        assertEquals(RoutineDays.ALL, RoutineDays.WEEKDAYS or RoutineDays.WEEKEND)
    }

    @Test
    fun `alternar um dia liga e desliga`() {
        val mask = RoutineDays.toggle(0, DayOfWeek.WEDNESDAY)
        assertTrue(RoutineDays.contains(mask, DayOfWeek.WEDNESDAY))
        assertEquals(0, RoutineDays.toggle(mask, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun `padroes com nome e dias avulsos na ordem da semana do locale`() {
        assertEquals(RoutineDays.Pattern.Everyday, RoutineDays.pattern(RoutineDays.ALL))
        assertEquals(RoutineDays.Pattern.Weekdays, RoutineDays.pattern(RoutineDays.WEEKDAYS))
        assertEquals(RoutineDays.Pattern.Weekend, RoutineDays.pattern(RoutineDays.WEEKEND))
        assertEquals(RoutineDays.Pattern.None, RoutineDays.pattern(0))
        val mask = RoutineDays.bit(DayOfWeek.MONDAY) or RoutineDays.bit(DayOfWeek.SUNDAY)
        assertEquals(
            RoutineDays.Pattern.Custom(listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)),
            RoutineDays.pattern(mask, firstDayOfWeek = DayOfWeek.SUNDAY),
        )
    }
}
