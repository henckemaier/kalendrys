package com.kalendrys.app.domain

/**
 * Formatação de exibição dos exercícios: séries×reps e o descanso compacto. Só texto para a UI —
 * não há timer; o descanso é apenas um valor em segundos guardado por exercício.
 */
object ExerciseFormat {

    /** Séries e repetições num rótulo curto, ex.: 3 e 10 → "3×10". */
    fun setsReps(sets: Int, reps: Int): String = "$sets×$reps"

    /**
     * Descanso em segundos num rótulo compacto: &lt;60 → "45s"; múltiplo de minuto → "2min";
     * senão → "1m30s". Usado só para exibir (o valor cru fica na entidade).
     */
    fun restLabel(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}min"
        else -> "${seconds / 60}m${seconds % 60}s"
    }
}
