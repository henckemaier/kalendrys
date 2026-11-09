package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.domain.RoutineSnooze
import com.kalendrys.app.widget.KalendrysWidgets
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Início de bloco de rotina (alarme) e as ações da notificação — "Concluir", "Pular" e "Adiar" —,
 * inclusive com o app fechado.
 *
 * "Adiar" agenda um alarme one-off PRÓPRIO (não passa pelo [RoutineAlarms] do próximo início): o
 * PendingIntent aponta de volta para cá com [ACTION_SNOOZE_FIRE] e Uri próprio, então nunca colide
 * com o alarme estrutural. Espelha o padrão do [ReminderActionReceiver] no caminho de rotina.
 */
class RoutineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KalendrysApp
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1L)
        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        val action = intent.action
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_START -> {
                        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
                        val date = LocalDate.ofEpochDay(epochDay)
                        app.routineRepository.onAlarm(epochDay, minute).forEach { routine ->
                            app.routineNotifications.show(routine, date)
                        }
                    }
                    ACTION_COMPLETE -> {
                        app.routineRepository.complete(routineId, LocalDate.ofEpochDay(epochDay))
                        app.routineNotifications.cancel(routineId)
                    }
                    ACTION_SKIP -> {
                        // Contrato do Forja (T20): grava o override SKIP do dia e reagenda sem ela.
                        app.routineRepository.skipOccurrence(routineId, epochDay)
                        app.routineNotifications.cancel(routineId)
                    }
                    ACTION_SNOOZE -> {
                        app.routineNotifications.cancel(routineId)
                        scheduleSnooze(context, routineId, epochDay)
                    }
                    ACTION_SNOOZE_FIRE -> {
                        // Reexibe só se o bloco ainda existe, é ativo, ocorre no dia e não foi concluído.
                        val date = LocalDate.ofEpochDay(epochDay)
                        app.routineRepository.routineForNotification(routineId, date)?.let { routine ->
                            app.routineNotifications.show(routine, date)
                        }
                    }
                }
                // "A seguir" e as rotinas pendentes dos widgets mudaram.
                KalendrysWidgets.updateAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // Alarme one-off do adiamento: exato quando possível, como os demais avisos do app.
    private fun scheduleSnooze(context: Context, routineId: Long, epochDay: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = RoutineSnooze.triggerAt(System.currentTimeMillis())
        val operation = PendingIntent.getBroadcast(
            context,
            routineId.toInt(),
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(ACTION_SNOOZE_FIRE)
                .setData(AppLinks.routineSnoozeFire(routineId, epochDay))
                .putExtra(EXTRA_ROUTINE_ID, routineId)
                .putExtra(EXTRA_EPOCH_DAY, epochDay),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClockOrInexact(context, triggerAt, operation)
    }

    companion object {
        const val ACTION_START = "com.kalendrys.app.action.ROUTINE_START"
        const val ACTION_COMPLETE = "com.kalendrys.app.action.ROUTINE_COMPLETE"
        const val ACTION_SKIP = "com.kalendrys.app.action.ROUTINE_SKIP"
        const val ACTION_SNOOZE = "com.kalendrys.app.action.ROUTINE_SNOOZE"
        const val ACTION_SNOOZE_FIRE = "com.kalendrys.app.action.ROUTINE_SNOOZE_FIRE"
        const val EXTRA_EPOCH_DAY = "extra_epoch_day"
        const val EXTRA_MINUTE = "extra_minute"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"
    }
}
