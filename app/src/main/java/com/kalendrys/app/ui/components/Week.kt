package com.kalendrys.app.ui.components

import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/** Primeiro dia da semana do locale (domingo no Brasil), usado pelo calendário e pela semana das rotinas. */
fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek
