package com.kalendrys.app.domain

import java.time.DayOfWeek
import java.util.Locale

/**
 * O app é só em português do Brasil (v1.5.1), independente do idioma do aparelho. Toda formatação
 * de apresentação (datas, meses, moeda, dias da semana) usa [LOCALE], nunca `Locale.getDefault()`.
 * A semana começa no [FIRST_DAY_OF_WEEK] (domingo), em todo o app.
 *
 * O `MainActivity.attachBaseContext` aplica o mesmo [LOCALE] à configuração da Activity, para os
 * componentes do sistema (DatePicker do Material 3) também saírem em pt-BR, sem `Locale.setDefault`.
 * A exportação do histórico (HistoryCsv) não usa isto: tem formato próprio, estável.
 */
object AppLocale {

    val LOCALE: Locale = Locale.forLanguageTag("pt-BR")

    val FIRST_DAY_OF_WEEK: DayOfWeek = DayOfWeek.SUNDAY
}
