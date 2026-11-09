package com.kalendrys.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.widget.KalendrysWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val app = context.applicationContext as KalendrysApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Este alarme chegou na hora: marca "visto até agora" para a recuperação de perdidos
                // não recontá-lo depois (ex.: num reboot mais tarde). Antes do early-return abaixo.
                app.settingsRepository.setLastSeen(System.currentTimeMillis())
                // Lê a versão atual da tarefa (título editado, recorrência) e agenda a próxima ocorrência.
                val task = app.repository.onReminderFired(taskId) ?: return@launch
                ReminderNotifications.show(context, task)
                // O "próximo lembrete" do widget de resumo mudou, mesmo sem nada mudar no banco.
                KalendrysWidgets.updateAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
