package com.kalendrys.app.summary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.widget.KalendrysWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DailyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KalendrysApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.settingsRepository.settings.first()
                // Confere de novo: o resumo pode ter sido desligado depois de o alarme ser agendado.
                if (intent.action == ACTION_SUMMARY && settings.summaryEnabled) {
                    DailySummaryNotifier.show(context, app.todayRepository.current())
                }
                // Na virada do dia, "concluídas hoje" zera e os widgets precisam refletir isso.
                KalendrysWidgets.updateAll(context)
                app.dailyScheduler.scheduleAll(settings)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SUMMARY = "com.kalendrys.app.action.DAILY_SUMMARY"
        const val ACTION_DAY_CHANGED = "com.kalendrys.app.action.DAY_CHANGED"
    }
}
