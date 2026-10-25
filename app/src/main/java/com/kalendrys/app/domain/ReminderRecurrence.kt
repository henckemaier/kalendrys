package com.kalendrys.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Regras de recorrência dos lembretes, em horário local.
 *
 * O lembrete guarda só a primeira ocorrência (a "âncora"); as seguintes são sempre
 * calculadas a partir dela. Assim um lembrete mensal do dia 31 cai no dia 28/29 em
 * fevereiro e volta ao 31 em março, em vez de "escorregar" mês a mês.
 *
 * A âncora é um instante absoluto: em outro fuso o lembrete mantém o mesmo instante
 * (18:05 de Brasília toca às 17:05 em Manaus), como um evento de agenda.
 */
object ReminderRecurrence {

    /** Se [date] tem uma ocorrência (no horário da âncora). */
    fun occursOn(date: LocalDate, anchor: LocalDateTime, repeat: ReminderRepeat): Boolean {
        val anchorDate = anchor.toLocalDate()
        if (date.isBefore(anchorDate)) return false
        return when (repeat) {
            ReminderRepeat.NONE -> date == anchorDate
            ReminderRepeat.DAILY -> true
            ReminderRepeat.WEEKDAYS -> date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            ReminderRepeat.WEEKLY -> date.dayOfWeek == anchorDate.dayOfWeek
            ReminderRepeat.MONTHLY -> date.dayOfMonth == minOf(anchorDate.dayOfMonth, date.lengthOfMonth())
        }
    }

    /** Próxima ocorrência estritamente depois de [after]; `null` para um lembrete único que já passou. */
    fun nextOccurrence(anchor: LocalDateTime, repeat: ReminderRepeat, after: LocalDateTime): LocalDateTime? {
        val firstDate = maxOf(anchor.toLocalDate(), after.toLocalDate())
        // A maior distância entre ocorrências é a mensal (até 31 dias); 62 dias cobre com folga.
        for (offset in 0L until MAX_DAYS_TO_SEARCH) {
            val date = firstDate.plusDays(offset)
            val candidate = date.atTime(anchor.toLocalTime())
            if (candidate.isAfter(after) && occursOn(date, anchor, repeat)) return candidate
        }
        return null
    }

    /** Dias com ocorrência entre [start] e [end] (inclusive), em ordem. */
    fun occurrencesBetween(
        anchor: LocalDateTime,
        repeat: ReminderRepeat,
        start: LocalDate,
        end: LocalDate,
    ): List<LocalDate> =
        generateSequence(maxOf(start, anchor.toLocalDate())) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .filter { occursOn(it, anchor, repeat) }
            .toList()

    /** Próxima vez em que o relógio marca [time] depois de [after] (hoje, se ainda não passou; senão amanhã). */
    fun nextDailyAt(time: LocalTime, after: LocalDateTime): LocalDateTime {
        val today = after.toLocalDate().atTime(time)
        return if (today.isAfter(after)) today else today.plusDays(1)
    }

    /** Atalho em epoch millis: quando o próximo alarme deve tocar, ou `null` se não há mais nenhum. */
    fun nextTriggerMillis(reminderAt: Long?, repeat: ReminderRepeat, now: Long, zone: ZoneId): Long? {
        if (reminderAt == null) return null
        val anchor = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), zone)
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return nextOccurrence(anchor, repeat, after)?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    private const val MAX_DAYS_TO_SEARCH = 62L
}
