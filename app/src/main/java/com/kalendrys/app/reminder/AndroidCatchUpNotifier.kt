package com.kalendrys.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.widget.KalendrysWidgets
import java.time.Instant
import java.time.ZoneId

/** Saídas do [ReminderCatchUpRunner] no aparelho: notificações de lembrete, agregado e widgets. */
class AndroidCatchUpNotifier(
    private val context: Context,
    private val shoppingRepository: ShoppingRepository,
) : ReminderCatchUpRunner.Notifier {

    override suspend fun showReminder(task: Task, firedAt: Long) {
        val occurrenceDay = Instant.ofEpochMilli(firedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        ReminderNotifications.show(context, task, shoppingRepository.progressOf(task.shoppingListId), occurrenceDay)
    }

    /** Uma notificação agregada ("N lembretes perdidos") no canal de lembretes; tocar abre o app. */
    override fun showMissed(count: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            MISSED_REQUEST_CODE,
            AppLinks.openApp(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.resources.getQuantityString(R.plurals.missed_reminders_text, count, count)
        val notification = NotificationCompat.Builder(context, KalendrysApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(context.getString(R.string.missed_reminders_title))
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(MISSED_NOTIFICATION_ID, notification)
    }

    override suspend fun remindersChanged() {
        KalendrysWidgets.updateAll(context)
    }

    private companion object {
        // Fixos e fora da faixa dos ids de tarefa (Task.id.toInt(), positivos): evita colidir com eles.
        const val MISSED_NOTIFICATION_ID = -1001
        const val MISSED_REQUEST_CODE = -1001
    }
}
