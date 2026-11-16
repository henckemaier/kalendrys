package com.kalendrys.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.weekdayTimeLabel
import com.kalendrys.app.ui.theme.appearance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object KalendrysWidgets {

    suspend fun updateAll(context: Context) {
        // Sessão do widget aberta: update() só recompõe com o que o observe() já tem. O refresh faz o
        // "a seguir" ser recalculado com o relógio de agora (F-1 da v1.6.1, ver TodayRepository.refresh).
        (context.applicationContext as? KalendrysApp)?.todayRepository?.refresh()
        TasksWidget().updateAll(context)
        SummaryWidget().updateAll(context)
        CalendarWidget().updateAll(context)
    }

    /**
     * Esquema de cores dos widgets pela Aparência do app (ADR-0002, 12.5), lido do DataStore (não do
     * StateFlow da [KalendrysApp], que ainda pode estar vazio num processo aberto só para o widget).
     * Observado dentro da composição, como os dados: com a sessão do widget aberta, trocar a
     * Aparência recompõe sem esperar uma sessão nova.
     */
    fun schemes(context: Context): Flow<WidgetScheme> =
        (context.applicationContext as KalendrysApp).settingsRepository.settings
            .map { WidgetTheme.schemeFor(it.appearance()) }
            .distinctUntilChanged()

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** "18:05" hoje, "Amanhã, 09:00", "Qua, 09:00" nos demais dias (dia da semana pela tabela pt-BR). */
    fun reminderMoment(context: Context, at: LocalDateTime, today: LocalDate): String = when (at.toLocalDate()) {
        today -> at.format(TIME)
        today.plusDays(1) -> context.getString(R.string.widget_tomorrow_at, at.format(TIME))
        else -> context.resources.weekdayTimeLabel(at.toLocalDate(), at.toLocalTime())
    }
}
