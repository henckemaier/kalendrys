package com.kalendrys.app.ui.components

import android.content.res.Resources
import com.kalendrys.app.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

/*
 * Rótulos abreviados de dia e mês (ADR-0002, seção 3, decisão 6; L5 da v1.7.0): montados pelas
 * tabelas pt-BR do strings.xml (weekday_short e month_short), não pelos padrões "EEE"/"MMM" do
 * formatador, cuja saída ("qua", "qua.", "Qua") muda entre versões do Android e dados de idioma.
 * Usados nos widgets (não são Compose da UI) e no seletor de lembrete, por isso recebem Resources.
 * Os números (dia, mês, hora) são montados à mão, sem formatador nem Locale.
 */

/** "jan" … "dez". */
fun Resources.monthShortName(month: Month): String = getStringArray(R.array.month_short)[month.value - 1]

/** "Qua, 16 nov" (título do widget de tarefas). */
fun Resources.weekdayDayMonthLabel(date: LocalDate): String =
    "${weekdayName(date.dayOfWeek, WeekdayName.SHORT)}, ${date.dayOfMonth} ${monthShortName(date.month)}"

/** "Qua, 30/10" (seletor de lembrete, mesmo ano). */
fun Resources.weekdayNumericDateLabel(date: LocalDate): String =
    "${weekdayName(date.dayOfWeek, WeekdayName.SHORT)}, ${twoDigits(date.dayOfMonth)}/${twoDigits(date.monthValue)}"

/** "Qua, 09:00" (próximo lembrete no widget de resumo, depois de amanhã). */
fun Resources.weekdayTimeLabel(date: LocalDate, time: LocalTime): String =
    "${weekdayName(date.dayOfWeek, WeekdayName.SHORT)}, ${twoDigits(time.hour)}:${twoDigits(time.minute)}"

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')
