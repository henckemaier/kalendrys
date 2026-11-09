package com.kalendrys.app.domain

/**
 * Tipo de uma categoria de rotina. Persistido pelo nome (TEXT) no Room — não renomear os valores.
 *
 * Por enquanto só [SIMPLE] é usado; [EXERCICIO] e [COMPRAS] são a costura (seam) para os módulos
 * de item que chegam depois (T22/T23). Um valor desconhecido na leitura cai no padrão
 * ([fromKey] → [SIMPLE]) em vez de derrubar o banco, no mesmo espírito de [RoutineIcon.fromKey] e
 * do fallback de [ReminderRepeat].
 */
enum class RoutineCategoryKind {
    SIMPLE,
    EXERCICIO,
    COMPRAS,
    ;

    companion object {
        val DEFAULT = SIMPLE

        fun fromKey(key: String): RoutineCategoryKind = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
