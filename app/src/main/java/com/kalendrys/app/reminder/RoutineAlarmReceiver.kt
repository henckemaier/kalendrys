package com.kalendrys.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.widget.KalendrysWidgets
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Início de bloco de rotina (alarme) e "Concluir" na notificação, inclusive com o app fechado. */
class RoutineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KalendrysApp
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1L)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_START -> {
                        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
                        val date = LocalDate.ofEpochDay(epochDay)
                        app.routineRepository.onAlarm(epochDay, minute).forEach { routine ->
                            app.routineNotifications.show(routine, date)
                        }
                    }
                    ACTION_COMPLETE -> {
                        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
                        app.routineRepository.complete(routineId, LocalDate.ofEpochDay(epochDay))
                        app.routineNotifications.cancel(routineId)
                    }
                }
                // "A seguir" e as rotinas pendentes dos widgets mudaram.
                KalendrysWidgets.updateAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.kalendrys.app.action.ROUTINE_START"
        const val ACTION_COMPLETE = "com.kalendrys.app.action.ROUTINE_COMPLETE"
        const val EXTRA_EPOCH_DAY = "extra_epoch_day"
        const val EXTRA_MINUTE = "extra_minute"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"
    }
}
