package com.kalendrys.app.domain

/**
 * Tipo da rotina (v1.10.0, ADR-0002 16.10.1): [NONE] ou [EXERCISE]. Só o tipo Exercício tem conteúdo,
 * os exercícios. Persistido pelo nome (TEXT) na coluna `routines.kind`; não renomear os valores. Um
 * valor desconhecido na leitura cai no padrão ([fromKey]), como o [RoutineIcon.fromKey].
 */
enum class RoutineKind {
    NONE,
    EXERCISE,
    ;

    companion object {
        val DEFAULT = NONE

        fun fromKey(key: String?): RoutineKind = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
