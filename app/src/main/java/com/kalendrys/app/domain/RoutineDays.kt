package com.kalendrys.app.domain

import java.time.DayOfWeek

/** Dias da semana de uma rotina como máscara de bits: bit 0 = segunda … bit 6 = domingo. */
object RoutineDays {

    const val ALL = 0b1111111
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000

    /** Como resumir os dias em texto: os conjuntos comuns têm nome próprio. */
    sealed interface Pattern {
        data object Everyday : Pattern
        data object Weekdays : Pattern
        data object Weekend : Pattern
        data object None : Pattern
        data class Custom(val days: List<DayOfWeek>) : Pattern
    }

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)

    /** Os dias marcados, na ordem da semana que começa em [firstDayOfWeek]. */
    fun days(mask: Int, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): List<DayOfWeek> =
        (0L until 7L).map { firstDayOfWeek.plus(it) }.filter { contains(mask, it) }

    fun pattern(mask: Int, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): Pattern = when (mask and ALL) {
        ALL -> Pattern.Everyday
        WEEKDAYS -> Pattern.Weekdays
        WEEKEND -> Pattern.Weekend
        0 -> Pattern.None
        else -> Pattern.Custom(days(mask, firstDayOfWeek))
    }
}
