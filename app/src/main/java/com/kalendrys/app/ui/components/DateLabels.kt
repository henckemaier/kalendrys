package com.kalendrys.app.ui.components

import com.kalendrys.app.domain.AppLocale
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// Interpretar um padrão de data custa; o calendário pede dezenas de rótulos por mês.
// DateTimeFormatter é imutável e thread-safe, então um por padrão basta (o idioma é sempre o do app).
private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

private fun formatter(pattern: String): DateTimeFormatter =
    formatters.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern, AppLocale.LOCALE) }

/** "Sábado, 24 de outubro", sempre em pt-BR ([AppLocale]). */
fun weekdayDateLabel(date: LocalDate): String =
    date.format(formatter("EEEE, d 'de' MMMM")).replaceFirstChar { it.titlecase(AppLocale.LOCALE) }

/** "24 de outubro de 2026". */
fun longDateLabel(date: LocalDate): String =
    date.format(formatter("d 'de' MMMM 'de' yyyy"))

/** "Outubro de 2026". */
fun monthYearLabel(month: YearMonth): String =
    month.format(formatter("MMMM 'de' yyyy")).replaceFirstChar { it.titlecase(AppLocale.LOCALE) }
