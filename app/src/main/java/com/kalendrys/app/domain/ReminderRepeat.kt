package com.kalendrys.app.domain

/** Como o lembrete se repete. Persistido pelo nome no Room — não renomear os valores. */
enum class ReminderRepeat {
    NONE,
    DAILY,

    /** Segunda a sexta. */
    WEEKDAYS,

    /** No mesmo dia da semana da primeira ocorrência. */
    WEEKLY,

    /** No mesmo dia do mês da primeira ocorrência (ou no último dia, em meses mais curtos). */
    MONTHLY,

    /**
     * Recorrência personalizada, definida pelos campos extras da tarefa
     * ([com.kalendrys.app.data.local.entity.Task.intervalEvery], `intervalUnit`, `byWeekdays`):
     * ou "a cada N dias/semanas/meses", ou em dias-da-semana escolhidos (seg/qua/sex…).
     * Sempre no fim do enum — os valores são persistidos pelo nome e não podem ser reordenados.
     */
    CUSTOM,
}

/** Unidade do intervalo de uma recorrência [ReminderRepeat.CUSTOM] "a cada N". Persistida pelo nome. */
enum class RecurrenceUnit {
    DAYS,
    WEEKS,
    MONTHS,
}
