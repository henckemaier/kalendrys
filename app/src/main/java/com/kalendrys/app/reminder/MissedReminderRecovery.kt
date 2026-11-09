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
import com.kalendrys.app.domain.MissedReminders
import com.kalendrys.app.domain.TaskSchedule
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Recupera lembretes que deveriam ter disparado enquanto o app não estava de pé para entregá-los
 * (aparelho desligado, Doze, "forçar parada"): detecta-os com [MissedReminders] e avisa numa
 * ÚNICA notificação agregada, depois avança o last-seen. Chamado no boot ([BootReceiver]) e
 * quando o app vem ao foreground ([com.kalendrys.app.MainActivity]).
 *
 * Idempotente: como avança o last-seen para `now`, rodar de novo não reconta nem gera spam. Sem
 * duplo-disparo com o reagendamento — este só NOTIFICA as ocorrências `<= now`; o reagendamento
 * cuida das `> now`.
 */
object MissedReminderRecovery {

    suspend fun run(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext as KalendrysApp
        val settings = app.settingsRepository
        val lastSeen = settings.lastSeenAt()
        // Primeira execução de sempre: estabelece a linha de base, sem "recuperar" o passado todo.
        if (lastSeen == null) {
            settings.setLastSeen(now)
            return
        }
        if (now > lastSeen) {
            val zone = ZoneId.systemDefault()
            val tasks = app.repository.tasks.first()
            val lastCompletions = app.repository.lastCompletions.first()
            // Não avisa sobre tarefa única já concluída: não há o que recuperar.
            val active = tasks.filterNot { TaskSchedule.isFinished(it, lastCompletions[it.id], zone) }
            val missed = MissedReminders.between(active, lastSeen, now, zone)
            if (missed.isNotEmpty()) notifyMissed(context, missed.size)
        }
        settings.setLastSeen(now)
    }

    /** Uma notificação agregada ("N lembretes perdidos") no canal de lembretes; toca abre o app. */
    private fun notifyMissed(context: Context, count: Int) {
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

    // Fixos e fora da faixa dos ids de tarefa (Task.id.toInt(), positivos): evita colidir com eles.
    private const val MISSED_NOTIFICATION_ID = -1001
    private const val MISSED_REQUEST_CODE = -1001
}
