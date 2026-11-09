package com.kalendrys.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderSnooze

/**
 * Monta e exibe a notificação de lembrete, com as ações "Concluir" e "Adiar". Compartilhada pelo
 * [ReminderReceiver] (quando o alarme toca) e pelo [ReminderActionReceiver] (ao reexibir depois de
 * adiar), para o visual e as ações ficarem num lugar só.
 */
object ReminderNotifications {

    fun show(context: Context, task: Task) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            task.id.toInt(),
            AppLinks.openTask(context, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, KalendrysApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(task.title)
            .setContentText(task.notes.ifBlank { context.getString(R.string.reminder_text) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .addAction(
                0,
                context.getString(R.string.reminder_complete_action),
                action(context, ReminderActionReceiver.ACTION_COMPLETE, task.id, AppLinks.reminderComplete(task.id)),
            )
            .addAction(
                0,
                context.getString(R.string.reminder_snooze_action, ReminderSnooze.DEFAULT_MINUTES),
                action(context, ReminderActionReceiver.ACTION_SNOOZE, task.id, AppLinks.reminderSnooze(task.id)),
            )
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(task.id.toInt(), notification)
    }

    // Uri próprio por tarefa e ação: extras não diferenciam PendingIntents, o Uri sim.
    private fun action(context: Context, action: String, taskId: Long, data: Uri): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .setData(data)
                .putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
