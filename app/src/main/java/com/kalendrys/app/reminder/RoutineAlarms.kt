package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Os dois alarmes das rotinas (v1.9.1, E5-B), independentes, calculados juntos no
 * [com.kalendrys.app.data.repository.RoutineRepository]; aqui só se agenda ou cancela cada um:
 *
 * - AVISO ([RoutineAlarmReceiver.ACTION_START]): o próximo início em que alguma rotina avisa e não
 *   foi concluída no dia. Despertador (exato, acorda o aparelho): o aviso não depende de nenhum
 *   alarme silencioso antes dele.
 * - WIDGETS ([RoutineAlarmReceiver.ACTION_REFRESH]): o próximo início de qualquer bloco, só quando
 *   vem ANTES do aviso (ou não há aviso). Silencioso (RTC, não acorda): redesenha o "a seguir" dos
 *   widgets quando o aparelho acordar.
 *
 * Até a v1.9.0 era um alarme só; um início silencioso antes do aviso virava RTC e o aviso só era
 * agendado quando ele disparava, então com o aparelho dormindo o aviso podia se perder.
 *
 * Reagendar é idempotente (cada alarme tem o seu PendingIntent, como no
 * [com.kalendrys.app.summary.DailyScheduler]); horário nulo cancela o alarme.
 *
 * Os horários chegam como INSTANTE pronto (v1.10.0, OBS-1): a conversão do início local para o
 * instante é do repositório, no mesmo fuso em que ele calculou o início. O alarme não leva extras:
 * quem dispara só chama o catch-up, que recalcula tudo.
 */
open class RoutineAlarms(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * [remindAtMillis]: o AVISO; [refreshAtMillis]: o dos WIDGETS, ambos em milissegundos desde a
     * época. Cada um nulo cancela o seu alarme.
     */
    open fun schedule(remindAtMillis: Long?, refreshAtMillis: Long?) {
        place(RoutineAlarmReceiver.ACTION_START, REMIND_REQUEST_CODE, remindAtMillis) { triggerAt, operation ->
            alarmManager.setAlarmClockOrInexact(context, triggerAt, operation)
        }
        place(RoutineAlarmReceiver.ACTION_REFRESH, REFRESH_REQUEST_CODE, refreshAtMillis) { triggerAt, operation ->
            alarmManager.set(AlarmManager.RTC, triggerAt, operation)
        }
    }

    private inline fun place(action: String, requestCode: Int, atMillis: Long?, set: (Long, PendingIntent) -> Unit) {
        val operation = pendingIntent(action, requestCode)
        if (atMillis == null) alarmManager.cancel(operation) else set(atMillis, operation)
    }

    // A ação e o requestCode diferenciam os PendingIntents. Cada alarme é sempre o mesmo, com o horário
    // atualizado; o da v1.9.x tinha extras de dia e minuto, que não entram na igualdade do
    // PendingIntent, então este o substitui e o cancela.
    private fun pendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RoutineAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        /** O do alarme único até a v1.9.0: quem atualiza não fica com um alarme órfão. */
        const val REMIND_REQUEST_CODE = 0
        const val REFRESH_REQUEST_CODE = 1
    }
}
