package com.kalendrys.app.data.settings

import java.time.LocalTime

/** Preferências do usuário. Os valores padrão valem até o primeiro ajuste. */
data class AppSettings(
    val summaryEnabled: Boolean = true,
    val summaryTime: LocalTime = DEFAULT_SUMMARY_TIME,
) {
    companion object {
        val DEFAULT_SUMMARY_TIME: LocalTime = LocalTime.of(8, 0)

        /** O horário é guardado como minutos desde a meia-noite (precisão de minuto basta). */
        fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

        fun timeFromMinuteOfDay(minute: Int): LocalTime =
            LocalTime.of((minute / 60).coerceIn(0, 23), minute % 60)
    }
}
