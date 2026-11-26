package com.kalendrys.app.domain


/**
 * Regra P4 do ADR-0001: quando uma conclusão reinicia as marcações da lista do dono, e a chave que
 * identifica esse reinício (para o "Desfazer" da conclusão poder revertê-lo).
 */
object ShoppingReset {

    /** Tarefa: só a que se repete reinicia; tarefa única concluída mantém as marcações. */
    fun taskResets(repeat: ReminderRepeat): Boolean = repeat != ReminderRepeat.NONE

    fun taskKey(completionLogId: Long): String = "task-log:$completionLogId"

}
