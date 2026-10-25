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
}
