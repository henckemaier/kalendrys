package com.kalendrys.app.summary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.widget.KalendrysWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarmes diários: o do resumo e o da virada do dia só ACORDAM o app. Os dois passam pelo
 * catch-up do resumo (ADR-0003, 4.3.2), que decide se o resumo sai e só depois reagenda; um alarme
 * atrasado que chegue depois de o resumo ter saído encontra a janela vazia.
 */
class DailyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KalendrysApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.summaryCatchUp.run()
                // Na virada do dia, "concluídas hoje" zera e os widgets precisam refletir isso.
                KalendrysWidgets.updateAll(context)
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
