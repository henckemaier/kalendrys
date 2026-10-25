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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Aviso de início de um bloco de rotina, com a ação "Concluir". */
class RoutineNotifications(private val context: Context) {

    fun show(routine: Routine, date: LocalDate) {
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
        // Uri próprio por rotina e dia: extras não diferenciam PendingIntents.
        val completeIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(RoutineAlarmReceiver.ACTION_COMPLETE)
                .setData(AppLinks.routineOccurrence(routine.id, date.toEpochDay()))
                .putExtra(RoutineAlarmReceiver.EXTRA_ROUTINE_ID, routine.id)
                .putExtra(RoutineAlarmReceiver.EXTRA_EPOCH_DAY, date.toEpochDay()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, KalendrysApp.ROUTINE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(routine.title)
            .setContentText(timeLabel(routine))
            .setContentIntent(tapIntent)
            .addAction(0, context.getString(R.string.routine_complete_action), completeIntent)
            .setAutoCancel(true)
            // Some sozinha quando o bloco termina; sem duração, depois de uma hora.
            .setTimeoutAfter(maxOf(routine.durationMinutes, NO_DURATION_TIMEOUT_MINUTES) * 60_000L)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, requestCode, notification)
    }

    fun cancel(routineId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, routineId.toInt())
    }

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
