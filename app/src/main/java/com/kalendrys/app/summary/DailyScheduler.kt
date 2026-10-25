package com.kalendrys.app.summary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.domain.ReminderRecurrence
import com.kalendrys.app.reminder.setAlarmClockOrInexact
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Alarmes diários do app: o resumo (exato, no horário das configurações, ou cancelado se
 * desligado) e a virada do dia, que só atualiza os widgets (inexato). Reagendar é idempotente.
 */
class DailyScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleAll(settings: AppSettings, now: LocalDateTime = LocalDateTime.now()) {
        val zone = ZoneId.systemDefault()
        val summary = pendingIntent(DailyReceiver.ACTION_SUMMARY)
        if (settings.summaryEnabled) {
            val summaryAt = ReminderRecurrence.nextDailyAt(settings.summaryTime, now).atZone(zone).toInstant().toEpochMilli()
            alarmManager.setAlarmClockOrInexact(context, summaryAt, summary)
        } else {
            alarmManager.cancel(summary)
        }

        val dayChangeAt = ReminderRecurrence.nextDailyAt(DAY_CHANGE_TIME, now).atZone(zone).toInstant().toEpochMilli()
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, dayChangeAt, pendingIntent(DailyReceiver.ACTION_DAY_CHANGED))
    }

    private fun pendingIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DailyReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        val DAY_CHANGE_TIME: LocalTime = LocalTime.of(0, 1)
    }
}
