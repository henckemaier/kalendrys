package com.kalendrys.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.KalendrysApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarme de lembrete: só ACORDA o app e roda o catch-up (ADR-0003), que entrega tudo o que venceu
 * em `(lastSeen, agora]`, não só a tarefa deste alarme. Um alarme atrasado que chegue depois de o
 * app já ter entregado a ocorrência encontra a janela vazia e não mostra nada.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KalendrysApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.reminderCatchUp.run()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
