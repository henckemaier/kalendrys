package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.domain.ReminderSnooze
import com.kalendrys.app.widget.KalendrysWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ações da notificação de lembrete — "Concluir" e "Adiar" — e o toque do alarme de adiamento,
 * tudo funcionando com o app fechado. exported=false: só recebe os PendingIntents do próprio app.
 *
 * O "Adiar" agenda um alarme one-off PRÓPRIO (não passa pelo ReminderScheduler estrutural): o
 * PendingIntent aponta para este receiver com [ACTION_SNOOZE_FIRE] e Uri próprio, então nunca
 * colide com o alarme da próxima ocorrência que o scheduler mantém para a mesma tarefa.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId < 0) return
        val app = context.applicationContext as KalendrysApp
        val action = intent.action
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_COMPLETE -> {
                        // Conclusão GUARDADA: taskForReminder devolve null se a ocorrência já está
                        // concluída hoje (recorrente), a tarefa já se encerrou (one-time) ou foi
                        // excluída. Aí só dispensa o aviso, sem gravar um segundo CompletionLog no
                        // mesmo dia (que duplicaria o histórico/CSV e o "Desfazer" só tiraria um).
                        if (app.repository.taskForReminder(taskId) != null) {
                            app.repository.logCompletion(taskId)
                            // O "próximo lembrete" e as pendências dos widgets mudaram.
                            KalendrysWidgets.updateAll(context)
                        }
                        NotificationManagerCompat.from(context).cancel(taskId.toInt())
                    }
                    ACTION_SNOOZE -> {
                        NotificationManagerCompat.from(context).cancel(taskId.toInt())
                        scheduleSnooze(context, taskId)
                    }
                    ACTION_SNOOZE_FIRE -> {
                        // Reexibe só se a tarefa ainda existe e não foi concluída nesse meio-tempo.
                        val task = app.repository.taskForReminder(taskId) ?: return@launch
                        ReminderNotifications.show(context, task)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    // Alarme one-off do adiamento: exato quando possível, como os demais avisos do app.
    private fun scheduleSnooze(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = ReminderSnooze.triggerAt(System.currentTimeMillis())
        val operation = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(ACTION_SNOOZE_FIRE)
                .setData(AppLinks.reminderSnoozeFire(taskId))
                .putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClockOrInexact(context, triggerAt, operation)
    }

    companion object {
        const val ACTION_COMPLETE = "com.kalendrys.app.action.REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "com.kalendrys.app.action.REMINDER_SNOOZE"
        const val ACTION_SNOOZE_FIRE = "com.kalendrys.app.action.REMINDER_SNOOZE_FIRE"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
