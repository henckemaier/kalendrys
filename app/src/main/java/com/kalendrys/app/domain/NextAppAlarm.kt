package com.kalendrys.app.domain

/** De onde vem o próximo aviso do Kalendrys. */
enum class AppAlarmKind { TASK, ROUTINE, SUMMARY }

/** O próximo aviso do Kalendrys: quando ([atMillis], epoch millis) e de que tipo. */
data class NextAppAlarm(val atMillis: Long, val kind: AppAlarmKind)

object NextAppAlarms {

    /**
     * O mais próximo entre os candidatos (cada um `null` quando não há nada daquele tipo); `null`
     * sem nenhum. Empate no mesmo instante: tarefa, depois rotina, depois resumo (a ordem do enum).
     */
    fun earliest(task: Long?, routine: Long?, summary: Long?): NextAppAlarm? =
        listOfNotNull(
            task?.let { NextAppAlarm(it, AppAlarmKind.TASK) },
            routine?.let { NextAppAlarm(it, AppAlarmKind.ROUTINE) },
            summary?.let { NextAppAlarm(it, AppAlarmKind.SUMMARY) },
        ).minWithOrNull(compareBy<NextAppAlarm> { it.atMillis }.thenBy { it.kind.ordinal })
}
