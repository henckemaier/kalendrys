package com.kalendrys.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarCalculatorTest {

    private val saoPaulo: ZoneId = ZoneId.of("America/Sao_Paulo")

    @Test
    fun `grade de outubro de 2026 comecando no domingo tem 4 celulas vazias antes do dia 1`() {
        // 1º de outubro de 2026 é uma quinta-feira.
        val grid = CalendarCalculator.monthGrid(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)

        assertEquals(35, grid.size)
        (0..3).forEach { assertNull(grid[it]) }
        assertEquals(LocalDate.of(2026, 10, 1), grid[4])
        assertEquals(LocalDate.of(2026, 10, 31), grid.last())
    }

    @Test
    fun `grade completa a ultima semana quando a semana comeca na segunda`() {
        val grid = CalendarCalculator.monthGrid(YearMonth.of(2026, 10), DayOfWeek.MONDAY)

        assertEquals(35, grid.size)
        assertEquals(LocalDate.of(2026, 10, 1), grid[3])
        assertEquals(LocalDate.of(2026, 10, 31), grid[33])
        assertNull(grid[34])
    }

    @Test
    fun `fevereiro que comeca no domingo cabe em exatamente quatro semanas`() {
        val grid = CalendarCalculator.monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)

        assertEquals(28, grid.size)
        assertFalse(grid.contains(null))
    }

    @Test
    fun `inicio da semana conforme o primeiro dia do locale`() {
        val saturday = LocalDate.of(2026, 10, 24)
        assertEquals(LocalDate.of(2026, 10, 18), CalendarCalculator.weekStart(saturday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 10, 19), CalendarCalculator.weekStart(saturday, DayOfWeek.MONDAY))
        // O próprio primeiro dia já é o início.
        assertEquals(LocalDate.of(2026, 10, 18), CalendarCalculator.weekStart(LocalDate.of(2026, 10, 18), DayOfWeek.SUNDAY))
    }

    @Test
    fun `agrupa pelo dia local e nao pelo dia em UTC`() {
        // 23:30 em São Paulo já é o dia seguinte em UTC.
        val lateNight = Instant.parse("2026-10-25T02:30:00Z").toEpochMilli()
        val grouped = CalendarCalculator.groupByDay(listOf(lateNight), saoPaulo) { it }

        assertEquals(setOf(LocalDate.of(2026, 10, 24)), grouped.keys)
    }

    @Test
    fun `itens de cada dia ficam em ordem cronologica`() {
        val afternoon = Instant.parse("2026-10-24T18:00:00Z").toEpochMilli()
        val morning = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        val grouped = CalendarCalculator.groupByDay(listOf(afternoon, morning), saoPaulo) { it }

        assertEquals(listOf(morning, afternoon), grouped[LocalDate.of(2026, 10, 24)])
    }

    @Test
    fun `itens sem horario ficam de fora`() {
        val grouped = CalendarCalculator.groupByDay(listOf<Long?>(null), saoPaulo) { it }

        assertEquals(emptyMap<LocalDate, List<Long?>>(), grouped)
    }
}
