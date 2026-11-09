package com.kalendrys.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineSnooze
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Aviso de início de um bloco de rotina, com a ação "Concluir". */
open class RoutineNotifications(private val context: Context) {

    open fun show(routine: Routine, date: LocalDate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val requestCode = routine.id.toInt()
        val tapIntent = PendingIntent.getActivity(
            context,
            requestCode,
            AppLinks.openRoutines(context, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, KalendrysApp.ROUTINE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(routine.title)
            .setContentText(timeLabel(routine))
            .setContentIntent(tapIntent)
            .addAction(
                0,
                context.getString(R.string.routine_complete_action),
                action(context, RoutineAlarmReceiver.ACTION_COMPLETE, routine.id, date, AppLinks.routineOccurrence(routine.id, date.toEpochDay())),
            )
            .addAction(
                0,
                context.getString(R.string.routine_skip_action),
                action(context, RoutineAlarmReceiver.ACTION_SKIP, routine.id, date, AppLinks.routineSkip(routine.id, date.toEpochDay())),
            )
            .addAction(
                0,
                context.getString(R.string.routine_snooze_action, RoutineSnooze.DEFAULT_MINUTES),
                action(context, RoutineAlarmReceiver.ACTION_SNOOZE, routine.id, date, AppLinks.routineSnooze(routine.id, date.toEpochDay())),
            )
            .setAutoCancel(true)
            // Some sozinha quando o bloco termina; sem duração, depois de uma hora.
            .setTimeoutAfter(maxOf(routine.durationMinutes, NO_DURATION_TIMEOUT_MINUTES) * 60_000L)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, requestCode, notification)
    }

    open fun cancel(routineId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, routineId.toInt())
    }

    // Uri próprio por rotina, dia e ação: extras não diferenciam PendingIntents, o Uri sim.
    private fun action(
        context: Context,
        action: String,
        routineId: Long,
        date: LocalDate,
        data: android.net.Uri,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            routineId.toInt(),
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(action)
                .setData(data)
                .putExtra(RoutineAlarmReceiver.EXTRA_ROUTINE_ID, routineId)
                .putExtra(RoutineAlarmReceiver.EXTRA_EPOCH_DAY, date.toEpochDay()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun timeLabel(routine: Routine): String {
        val start = timeOf(routine.startMinute)
        if (routine.durationMinutes == 0) return start
        return "$start–${timeOf(routine.endMinute)} · ${DurationFormat.format(routine.durationMinutes)}"
    }

    private fun timeOf(minute: Int): String = LocalTime.of(minute % 1440 / 60, minute % 60).format(TIME)

    private companion object {
        // Tag própria: os ids das rotinas e das tarefas começam ambos em 1.
        const val TAG = "routine"
        const val NO_DURATION_TIMEOUT_MINUTES = 60
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
