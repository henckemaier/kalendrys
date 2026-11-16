package com.kalendrys.app.domain

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Horário do resumo diário, calculado num lugar só: o [com.kalendrys.app.summary.DailyScheduler]
 * agenda este instante e o diagnóstico de alarmes mostra o mesmo. A virada do dia (00:01) não
 * entra: ela só redesenha os widgets e não avisa nada.
 */
object DailySummarySchedule {

    /** Próximo resumo estritamente depois de [now], em epoch millis; `null` com o resumo desligado. */
    fun nextAt(enabled: Boolean, time: LocalTime, now: LocalDateTime, zone: ZoneId): Long? =
        if (enabled) ReminderRecurrence.nextDailyAt(time, now).atZone(zone).toInstant().toEpochMilli() else null
}
