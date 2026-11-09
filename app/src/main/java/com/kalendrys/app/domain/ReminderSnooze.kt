package com.kalendrys.app.domain

/**
 * Cálculo puro do adiamento ("Adiar") de um lembrete: devolve o instante (epoch millis) em que ele
 * deve tocar de novo. Isolado do receiver para ficar testável sem Android/AlarmManager.
 */
object ReminderSnooze {

    /** Minutos padrão do "Adiar" na notificação. */
    const val DEFAULT_MINUTES = 10

    /**
     * Novo horário do toque: [now] + [minutes]. [minutes] é forçado a no mínimo 1 para o alarme
     * nunca cair no passado nem no mesmo instante de [now].
     */
    fun triggerAt(now: Long, minutes: Int = DEFAULT_MINUTES): Long =
        now + minutes.coerceAtLeast(1) * 60_000L
}
