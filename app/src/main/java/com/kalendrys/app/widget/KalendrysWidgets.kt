package com.kalendrys.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import com.kalendrys.app.R
import com.kalendrys.app.ui.theme.DarkColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object KalendrysWidgets {

    /** Paleta escura do app sempre, mesmo com o sistema no tema claro: o texto claro combina com o fundo escuro. */
    val colors = ColorProviders(scheme = DarkColors)

    /** Fundo escuro com 75% de opacidade: deixa o papel de parede aparecer sem perder a leitura. */
    val background = ColorProvider(Color(0xBF0C0E13))

    suspend fun updateAll(context: Context) {
        TasksWidget().updateAll(context)
        SummaryWidget().updateAll(context)
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val WEEKDAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, HH:mm")

    /** "18:05" hoje, "Amanhã, 09:00", "qua., 09:00" nos demais dias. */
    fun reminderMoment(context: Context, at: LocalDateTime, today: LocalDate): String = when (at.toLocalDate()) {
        today -> at.format(TIME)
        today.plusDays(1) -> context.getString(R.string.widget_tomorrow_at, at.format(TIME))
        else -> at.format(WEEKDAY_TIME)
    }
}
