package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/** Alarme exato que acorda o aparelho, usado por lembretes, resumo diário e rotinas. */
internal fun AlarmManager.setExactOrInexact(triggerAtMillis: Long, operation: PendingIntent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
        // Usuário revogou "Alarmes e lembretes": degrada para alarme inexato em vez de crashar.
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    } else {
        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }
}
