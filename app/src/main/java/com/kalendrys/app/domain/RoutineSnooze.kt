package com.kalendrys.app.domain

/**
 * Cálculo puro do adiamento ("Adiar") do aviso de um bloco de rotina: devolve o instante (epoch
 * millis) em que o aviso deve reaparecer. Isolado do receiver para ficar testável sem Android.
 * Espelha o [ReminderSnooze] das tarefas, mas no caminho de rotina (pode ter padrão próprio).
 */
object RoutineSnooze {

    /** Minutos padrão do "Adiar" no aviso de bloco. */
    const val DEFAULT_MINUTES = 10

    /**
     * Novo horário do aviso: [now] + [minutes]. [minutes] é forçado a no mínimo 1 para o alarme
     * nunca cair no passado nem no mesmo instante de [now].
     */
    fun triggerAt(now: Long, minutes: Int = DEFAULT_MINUTES): Long =
        now + minutes.coerceAtLeast(1) * 60_000L
}
