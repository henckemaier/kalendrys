package com.kalendrys.app.domain

import java.time.LocalDate

/**
 * Regra P4 do ADR-0001: quando uma conclusão reinicia as marcações da lista do dono, e a chave que
 * identifica esse reinício (para o "Desfazer" da conclusão poder revertê-lo).
 */
object ShoppingReset {

    /** Tarefa: só a que se repete reinicia; tarefa única concluída mantém as marcações. */
    fun taskResets(repeat: ReminderRepeat): Boolean = repeat != ReminderRepeat.NONE

    /** Bloco de rotina: só a ocorrência de hoje reinicia; corrigir um dia passado não mexe na lista. */
    fun routineResets(occurrence: LocalDate, today: LocalDate): Boolean = occurrence == today

    fun taskKey(completionLogId: Long): String = "task-log:$completionLogId"

    fun routineKey(routineId: Long, epochDay: Long): String = "routine:$routineId:$epochDay"
}
