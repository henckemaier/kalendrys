package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.kalendrys.app.AppLinks

/**
 * Alarme no horário certo para lembretes, avisos de rotina e resumo diário.
 *
 * Usa `setAlarmClock` (o tipo dos apps de despertador) em vez de `setExactAndAllowWhileIdle`:
 * alguns fabricantes (ColorOS/realme, por exemplo) esticam os alarmes exatos de apps de
 * terceiros para uma janela de até 1 hora, mas não mexem nos de despertador. O custo é o ícone
 * de despertador na barra de status e o "próximo alarme" na tela de bloqueio.
 */
internal fun AlarmManager.setAlarmClockOrInexact(context: Context, triggerAtMillis: Long, operation: PendingIntent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
        // Usuário revogou "Alarmes e lembretes": degrada para alarme inexato em vez de crashar.
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        return
    }
    setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context)), operation)
}

/**
 * O sistema deixa este app agendar alarmes no horário exato?
 *
 * Abaixo do Android 12 (SDK 31) a permissão não existe e sempre é concedida. A partir daí o
 * usuário pode revogar "Alarmes e lembretes" fora do app, então vale reler em `onResume`.
 * Com a permissão negada, [setAlarmClockOrInexact] degrada para alarmes inexatos e os avisos
 * podem atrasar.
 */
fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val manager = context.getSystemService(AlarmManager::class.java)
    return manager?.canScheduleExactAlarms() ?: true
}

/**
 * Intent para a tela "Alarmes e lembretes" do sistema, já filtrada neste app, onde o usuário
 * concede a permissão de alarme exato. Só faz sentido no Android 12+ (SDK 31+).
 */
fun requestExactAlarmPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))

/** O que abre ao tocar no "próximo alarme" do sistema: o app. */
private fun showIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        AppLinks.openApp(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

// Fora da faixa dos ids de tarefa e rotina (usados como requestCode dos outros PendingIntents).
private const val SHOW_REQUEST_CODE = Int.MAX_VALUE - 1
