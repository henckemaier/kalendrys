package com.kalendrys.app.domain

/**
 * Tipos de exceção pontual de uma rotina, guardados como TEXTO em
 * [com.kalendrys.app.data.local.entity.RoutineOverride.type]. Contrato de string compartilhado entre
 * quem LÊ a exceção ([RoutineSchedule]) e quem a ESCREVE (UI de exceção e ações da notificação): usar
 * sempre estas constantes, nunca literais soltos.
 */
object RoutineOverrideType {
    /** Pular a ocorrência daquele dia: a rotina não acontece, sem desligá-la. */
    const val SKIP = "SKIP"

    /** Remarcar a ocorrência daquele dia para outro minuto e/ou outro dia. */
    const val RESCHEDULE = "RESCHEDULE"
}
