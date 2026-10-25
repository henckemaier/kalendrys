package com.kalendrys.app.ui.components

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Interpretar um padrão de data custa; o calendário pede dezenas de rótulos por mês.
// DateTimeFormatter é imutável e thread-safe, então um por (padrão, idioma) basta.
private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

private fun formatter(pattern: String, locale: Locale): DateTimeFormatter =
    formatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

/** "Sábado, 24 de outubro" no idioma do aparelho. */
fun weekdayDateLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(formatter("EEEE, d 'de' MMMM", locale)).replaceFirstChar { it.titlecase(locale) }

/** "24 de outubro de 2026". */
fun longDateLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(formatter("d 'de' MMMM 'de' yyyy", locale))

/** "Outubro de 2026". */
fun monthYearLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.format(formatter("MMMM 'de' yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
