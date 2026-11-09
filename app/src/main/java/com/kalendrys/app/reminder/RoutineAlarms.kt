package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.domain.RoutineSchedule
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Um único alarme para todas as rotinas: o próximo horário em que algum bloco começa. Reagendar
 * é idempotente (mesmo PendingIntent), como no [com.kalendrys.app.summary.DailyScheduler].
 *
 * Se algum bloco daquele horário avisa, o alarme é exato e acorda o aparelho; senão ele só serve
 * para redesenhar os widgets ("a seguir") e espera o aparelho acordar.
 */
open class RoutineAlarms(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    open fun scheduleNext(
        routines: List<Routine>,
        after: LocalDateTime,
        overridesByRoutine: Map<Long, List<RoutineOverride>> = emptyMap(),
    ) {
        val zone = ZoneId.systemDefault()
        val next = RoutineSchedule.nextStart(routines, after, zone, overridesByRoutine)
        if (next == null) {
            alarmManager.cancel(pendingIntent(epochDay = 0, minute = 0))
            return
        }
        val triggerAt = next.at.atZone(zone).toInstant().toEpochMilli()
        val operation = pendingIntent(
            epochDay = next.at.toLocalDate().toEpochDay(),
            minute = next.at.hour * 60 + next.at.minute,
        )
        if (next.routines.any { it.remind }) {
            alarmManager.setAlarmClockOrInexact(context, triggerAt, operation)
        } else {
            alarmManager.set(AlarmManager.RTC, triggerAt, operation)
        }
    }

    // Extras não diferenciam PendingIntents: sempre o mesmo alarme, com o horário atualizado.
    private fun pendingIntent(epochDay: Long, minute: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(RoutineAlarmReceiver.ACTION_START)
                .putExtra(RoutineAlarmReceiver.EXTRA_EPOCH_DAY, epochDay)
                .putExtra(RoutineAlarmReceiver.EXTRA_MINUTE, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
