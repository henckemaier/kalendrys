package com.kalendrys.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Regras de recorrência dos lembretes, em horário local.
 *
 * O lembrete guarda só a primeira ocorrência (a "âncora"); as seguintes são sempre
 * calculadas a partir dela. Assim um lembrete mensal do dia 31 cai no dia 28/29 em
 * fevereiro e volta ao 31 em março, em vez de "escorregar" mês a mês.
 *
 * A âncora é um instante absoluto: em outro fuso o lembrete mantém o mesmo instante
 * (18:05 de Brasília toca às 17:05 em Manaus), como um evento de agenda.
 *
 * Recorrência [ReminderRepeat.CUSTOM]: definida pelos parâmetros extras, com dois sub-modos
 * decididos só pelos valores — se [byWeekdays] != 0, ocorre nos dias-da-semana escolhidos
 * (bitmask, SEG=bit0 … DOM=bit6); senão, "a cada [every] [unit]" a partir da âncora. Os demais
 * tipos ignoram os extras (parâmetros com default), então os callers não-custom não mudam.
 */
object ReminderRecurrence {

    /** Se [date] tem uma ocorrência (no horário da âncora). */
    fun occursOn(
        date: LocalDate,
        anchor: LocalDateTime,
        repeat: ReminderRepeat,
        every: Int = 1,
        unit: RecurrenceUnit? = null,
        byWeekdays: Int = 0,
    ): Boolean {
        val anchorDate = anchor.toLocalDate()
        if (date.isBefore(anchorDate)) return false
        return when (repeat) {
            ReminderRepeat.NONE -> date == anchorDate
            ReminderRepeat.DAILY -> true
            ReminderRepeat.WEEKDAYS -> date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            ReminderRepeat.WEEKLY -> date.dayOfWeek == anchorDate.dayOfWeek
            ReminderRepeat.MONTHLY -> date.dayOfMonth == minOf(anchorDate.dayOfMonth, date.lengthOfMonth())
            ReminderRepeat.CUSTOM -> customOccursOn(date, anchorDate, every, unit, byWeekdays)
        }
    }

    private fun customOccursOn(
        date: LocalDate,
        anchorDate: LocalDate,
        every: Int,
        unit: RecurrenceUnit?,
        byWeekdays: Int,
    ): Boolean {
        // Modo multi-dia tem prioridade: se há dias escolhidos, o intervalo é ignorado.
        if (byWeekdays != 0) return weekdaySelected(byWeekdays, date.dayOfWeek)
        val e = every.coerceAtLeast(1)
        return when (unit) {
            RecurrenceUnit.DAYS -> ChronoUnit.DAYS.between(anchorDate, date) % e == 0L
            // Mesmo dia-da-semana da âncora (garante múltiplo de 7 dias) e a cada [e] semanas.
            RecurrenceUnit.WEEKS -> date.dayOfWeek == anchorDate.dayOfWeek &&
                (ChronoUnit.DAYS.between(anchorDate, date) / 7) % e == 0L
            // Mesmo dia do mês, com o mesmo clamp do MONTHLY, e a cada [e] meses.
            RecurrenceUnit.MONTHS -> {
                val day = minOf(anchorDate.dayOfMonth, date.lengthOfMonth())
                date.dayOfMonth == day &&
                    ChronoUnit.MONTHS.between(anchorDate.withDayOfMonth(1), date.withDayOfMonth(1)) % e == 0L
            }
            null -> false // CUSTOM sem configuração utilizável: nunca ocorre.
        }
    }

    /** Bit (dayOfWeek.value - 1): SEGUNDA=bit0 … DOMINGO=bit6. */
    private fun weekdaySelected(mask: Int, day: DayOfWeek): Boolean =
        (mask and (1 shl (day.value - 1))) != 0

    /** Próxima ocorrência estritamente depois de [after]; `null` para um lembrete único que já passou. */
    fun nextOccurrence(
        anchor: LocalDateTime,
        repeat: ReminderRepeat,
        after: LocalDateTime,
        every: Int = 1,
        unit: RecurrenceUnit? = null,
        byWeekdays: Int = 0,
    ): LocalDateTime? {
        // CUSTOM é aritmético (sem varrer dia-a-dia): o intervalo mensal pode passar de 62 dias.
        if (repeat == ReminderRepeat.CUSTOM) return customNextOccurrence(anchor, after, every, unit, byWeekdays)
        val firstDate = maxOf(anchor.toLocalDate(), after.toLocalDate())
        // A maior distância entre ocorrências não-custom é a mensal (até 31 dias); 62 cobre com folga.
        for (offset in 0L until MAX_DAYS_TO_SEARCH) {
            val date = firstDate.plusDays(offset)
            val candidate = date.atTime(anchor.toLocalTime())
            if (candidate.isAfter(after) && occursOn(date, anchor, repeat)) return candidate
        }
        return null
    }

    private fun customNextOccurrence(
        anchor: LocalDateTime,
        after: LocalDateTime,
        every: Int,
        unit: RecurrenceUnit?,
        byWeekdays: Int,
    ): LocalDateTime? {
        val time = anchor.toLocalTime()
        val anchorDate = anchor.toLocalDate()
        val from = maxOf(anchorDate, after.toLocalDate())

        // Valida cada candidato pelo occursOn (fonte da verdade do clamp/seleção) e exige > after.
        fun firstValid(dates: Sequence<LocalDate>): LocalDateTime? =
            dates.map { it.atTime(time) }
                .firstOrNull {
                    it.isAfter(after) &&
                        occursOn(it.toLocalDate(), anchor, ReminderRepeat.CUSTOM, every, unit, byWeekdays)
                }

        if (byWeekdays != 0) {
            // No máximo a 7 dias do próximo dia-da-semana selecionado.
            return firstValid(generateSequence(from) { it.plusDays(1) }.take(8))
        }
        val e = every.coerceAtLeast(1)
        return when (unit) {
            RecurrenceUnit.DAYS -> {
                val rem = Math.floorMod(ChronoUnit.DAYS.between(anchorDate, from), e.toLong())
                val start = if (rem == 0L) from else from.plusDays(e - rem)
                firstValid(generateSequence(start) { it.plusDays(e.toLong()) }.take(2))
            }
            RecurrenceUnit.WEEKS -> {
                val toAnchorWeekday = Math.floorMod((anchorDate.dayOfWeek.value - from.dayOfWeek.value).toLong(), 7L)
                val onWeekday = from.plusDays(toAnchorWeekday)
                val weeks = ChronoUnit.DAYS.between(anchorDate, onWeekday) / 7
                val rem = Math.floorMod(weeks, e.toLong())
                val start = if (rem == 0L) onWeekday else onWeekday.plusWeeks(e - rem)
                firstValid(generateSequence(start) { it.plusWeeks(e.toLong()) }.take(2))
            }
            RecurrenceUnit.MONTHS -> {
                val anchorMonth = anchorDate.withDayOfMonth(1)
                var months = maxOf(0L, ChronoUnit.MONTHS.between(anchorMonth, from.withDayOfMonth(1)))
                val rem = months % e
                if (rem != 0L) months += (e - rem)
                val candidates = generateSequence(months) { it + e }.take(3).map { m ->
                    val ym = anchorMonth.plusMonths(m)
                    ym.withDayOfMonth(minOf(anchorDate.dayOfMonth, ym.lengthOfMonth()))
                }
                firstValid(candidates)
            }
            null -> null
        }
    }

    /** Dias com ocorrência entre [start] e [end] (inclusive), em ordem. */
    fun occurrencesBetween(
        anchor: LocalDateTime,
        repeat: ReminderRepeat,
        start: LocalDate,
        end: LocalDate,
        every: Int = 1,
        unit: RecurrenceUnit? = null,
        byWeekdays: Int = 0,
    ): List<LocalDate> =
        generateSequence(maxOf(start, anchor.toLocalDate())) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .filter { occursOn(it, anchor, repeat, every, unit, byWeekdays) }
            .toList()

    /** Próxima vez em que o relógio marca [time] depois de [after] (hoje, se ainda não passou; senão amanhã). */
    fun nextDailyAt(time: LocalTime, after: LocalDateTime): LocalDateTime {
        val today = after.toLocalDate().atTime(time)
        return if (today.isAfter(after)) today else today.plusDays(1)
    }

    /** Atalho em epoch millis: quando o próximo alarme deve tocar, ou `null` se não há mais nenhum. */
    fun nextTriggerMillis(
        reminderAt: Long?,
        repeat: ReminderRepeat,
        now: Long,
        zone: ZoneId,
        every: Int = 1,
        unit: RecurrenceUnit? = null,
        byWeekdays: Int = 0,
    ): Long? {
        if (reminderAt == null) return null
        val anchor = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), zone)
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return nextOccurrence(anchor, repeat, after, every, unit, byWeekdays)
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    private const val MAX_DAYS_TO_SEARCH = 62L
}
