package com.kalendrys.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Lógica pura do calendário: grade do mês e agrupamento de eventos por dia.
 * Sem dependências de Android — testável direto na JVM.
 */
object CalendarCalculator {

    /**
     * Células do mês em semanas completas, começando em [firstDayOfWeek].
     * `null` marca as células de outros meses que completam a primeira e a última semana.
     */
    fun monthGrid(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
        val leading = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val cells = ArrayList<LocalDate?>(42)
        repeat(leading) { cells.add(null) }
        for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day))
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    /** Primeiro dia da semana de [date], com a semana começando em [firstDayOfWeek]. */
    fun weekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
        date.minusDays(((date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong())

    /**
     * Agrupa itens pelo dia local do seu horário, em ordem cronológica dentro de cada dia.
     * Itens cujo [epochMillis] é `null` (ex.: tarefa sem lembrete) ficam de fora.
     */
    fun <T> groupByDay(items: Iterable<T>, zone: ZoneId, epochMillis: (T) -> Long?): Map<LocalDate, List<T>> =
        items.mapNotNull { item -> epochMillis(item)?.let { item to it } }
            .sortedBy { it.second }
            .groupBy(
                keySelector = { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() },
                valueTransform = { it.first },
            )
}
