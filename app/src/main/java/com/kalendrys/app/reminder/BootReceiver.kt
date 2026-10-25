package com.kalendrys.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.KalendrysApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Refaz os alarmes quando eles se perdem ou podem ter ficado errados: o AlarmManager os apaga
 * no reinício, e a "próxima ocorrência" de um recorrente é calculada a partir do relógio e do
 * fuso do aparelho — se o relógio estava errado ao agendar, a ocorrência agendada também está.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val app = context.applicationContext as KalendrysApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.rescheduleReminders()
                app.routineRepository.scheduleNextAlarm()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
