package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

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
 */
open class RoutineAlarms(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** [remindAt]: o AVISO; [refreshAt]: o dos WIDGETS. Cada um nulo cancela o seu alarme. */
    open fun schedule(remindAt: LocalDateTime?, refreshAt: LocalDateTime?) {
        val zone = ZoneId.systemDefault()
        place(RoutineAlarmReceiver.ACTION_START, REMIND_REQUEST_CODE, remindAt, zone) { triggerAt, operation ->
            alarmManager.setAlarmClockOrInexact(context, triggerAt, operation)
        }
        place(RoutineAlarmReceiver.ACTION_REFRESH, REFRESH_REQUEST_CODE, refreshAt, zone) { triggerAt, operation ->
            alarmManager.set(AlarmManager.RTC, triggerAt, operation)
        }
    }

    private inline fun place(
        action: String,
        requestCode: Int,
        at: LocalDateTime?,
        zone: ZoneId,
        set: (Long, PendingIntent) -> Unit,
    ) {
        if (at == null) {
            alarmManager.cancel(pendingIntent(action, requestCode, epochDay = 0, minute = 0))
            return
        }
        set(
            at.atZone(zone).toInstant().toEpochMilli(),
            pendingIntent(action, requestCode, epochDay = at.toLocalDate().toEpochDay(), minute = at.hour * 60 + at.minute),
        )
    }

    // Extras não diferenciam PendingIntents; a ação e o requestCode, sim. Cada alarme é sempre o mesmo,
    // com o horário atualizado.
    private fun pendingIntent(action: String, requestCode: Int, epochDay: Long, minute: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(action)
                .putExtra(RoutineAlarmReceiver.EXTRA_EPOCH_DAY, epochDay)
                .putExtra(RoutineAlarmReceiver.EXTRA_MINUTE, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        /** O do alarme único até a v1.9.0: quem atualiza não fica com um alarme órfão. */
        const val REMIND_REQUEST_CODE = 0
        const val REFRESH_REQUEST_CODE = 1
    }
}
