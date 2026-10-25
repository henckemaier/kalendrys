package com.kalendrys.app.summary

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
import com.kalendrys.app.domain.TodayOverview
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Notificação "Resumo do dia": tarefas pendentes e rotinas que ainda vêm, por horário. */
object DailySummaryNotifier {

    private const val NOTIFICATION_ID = Int.MAX_VALUE // não colide com os ids de tarefa
    private const val MAX_LINES = 5
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun show(context: Context, overview: TodayOverview, now: LocalTime = LocalTime.now()) {
        // O resumo é um retrato do momento: blocos de rotina que já terminaram ficam de fora.
        val routinesAhead = overview.pendingRoutines.filterNot { it.isOver(now) }
        // Dia sem pendências: não há o que resumir.
        if (overview.pending.isEmpty() && routinesAhead.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val res = context.resources
        val pendingCount = overview.pending.size
        // Partes com 0 ficam de fora (em pt o Android trataria "0 rotina" como singular).
        val text = listOfNotNull(
            pendingCount.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.summary_pending, it, it) },
            overview.remindersToday.takeIf { it > 0 }?.let {
                res.getQuantityString(R.plurals.summary_reminders, it, it)
            },
            routinesAhead.size.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.summary_routines, it, it) },
        ).joinToString(" · ")

        // Primeiro o que tem horário (lembretes de hoje e rotinas), em ordem; depois as tarefas sem horário.
        val timed = overview.pending.mapNotNull { task -> task.reminderToday?.let { it to task.title } } +
            routinesAhead.map { it.start to it.title }
        val lines = timed.sortedBy { it.first }.map { (time, title) -> "${time.format(TIME)}  $title" } +
            overview.pending.filter { it.reminderToday == null }.map { it.title }

        val title = context.getString(R.string.summary_title)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_LINES).forEach(style::addLine)
        val hidden = lines.size - MAX_LINES
        if (hidden > 0) style.setSummaryText(res.getQuantityString(R.plurals.summary_more, hidden, hidden))

        val tapIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            AppLinks.openApp(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, KalendrysApp.SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Já terminou hoje? Um bloco que passa da meia-noite (fim antes do início) ainda não terminou. */
    private fun TodayOverview.RoutineBlock.isOver(now: LocalTime): Boolean {
        val finish = end ?: start
        return !finish.isBefore(start) && !finish.isAfter(now)
    }
}
