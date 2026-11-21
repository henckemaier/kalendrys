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
import java.time.LocalDate
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
        // O dia da ocorrência que a notificação mostra (NB-E5-A); null em notificação antiga = hoje.
        val occurrenceDay = intent.occurrenceDay()
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_COMPLETE -> {
                        // Conclusão GUARDADA pelo dia da ocorrência: já concluída nesse dia (recorrente),
                        // encerrada (one-time) ou excluída só dispensa o aviso, sem gravar um segundo
                        // CompletionLog (que duplicaria o histórico/CSV e o "Desfazer" só tiraria um).
                        // Repetida de dia passado grava naquele dia (NB-E5-A).
                        if (app.repository.completeFromReminder(taskId, occurrenceDay)) {
                            // O "próximo lembrete" e as pendências dos widgets mudaram.
                            KalendrysWidgets.updateAll(context)
                        }
                        NotificationManagerCompat.from(context).cancel(taskId.toInt())
                    }
                    ACTION_SNOOZE -> {
                        NotificationManagerCompat.from(context).cancel(taskId.toInt())
                        scheduleSnooze(context, taskId, occurrenceDay)
                    }
                    ACTION_SNOOZE_FIRE -> {
                        // Reexibe só se a tarefa ainda existe e a ocorrência não foi concluída nesse meio-tempo;
                        // com o MESMO dia da ocorrência (NB-E5-A).
                        val task = app.repository.taskForReminder(taskId, occurrenceDay = occurrenceDay) ?: return@launch
                        ReminderNotifications.show(context, task, app.shoppingRepository.progressOf(task.shoppingListId), occurrenceDay)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    // Alarme one-off do adiamento: exato quando possível, como os demais avisos do app.
    private fun scheduleSnooze(context: Context, taskId: Long, occurrenceDay: LocalDate?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = ReminderSnooze.triggerAt(System.currentTimeMillis())
        val operation = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            snoozeFireIntent(context, taskId, occurrenceDay),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClockOrInexact(context, triggerAt, operation)
    }

    companion object {
        const val ACTION_COMPLETE = "com.kalendrys.app.action.REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "com.kalendrys.app.action.REMINDER_SNOOZE"
        const val ACTION_SNOOZE_FIRE = "com.kalendrys.app.action.REMINDER_SNOOZE_FIRE"
        const val EXTRA_TASK_ID = "extra_task_id"

        /** Dia da ocorrência (epochDay) que a notificação mostra (NB-E5-A); ausente em notificação antiga. */
        const val EXTRA_OCCURRENCE_DAY = "extra_occurrence_day"

        /** Intent do alarme do adiamento: leva o MESMO dia da ocorrência para a reexibição. */
        internal fun snoozeFireIntent(context: Context, taskId: Long, occurrenceDay: LocalDate?): Intent =
            ReminderNotifications.actionIntent(context, ACTION_SNOOZE_FIRE, taskId, AppLinks.reminderSnoozeFire(taskId), occurrenceDay)

        /** O dia da ocorrência no intent, ou null (notificação antiga, sem o dia: vale como hoje). */
        internal fun Intent.occurrenceDay(): LocalDate? =
            if (hasExtra(EXTRA_OCCURRENCE_DAY)) LocalDate.ofEpochDay(getLongExtra(EXTRA_OCCURRENCE_DAY, 0L)) else null
    }
}
