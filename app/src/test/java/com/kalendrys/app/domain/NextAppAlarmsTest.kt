package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Escolha do próximo aviso do Kalendrys entre os três candidatos (v1.7.1, E3). */
class NextAppAlarmsTest {

    private val task = 3_000L
    private val routine = 2_000L
    private val summary = 1_000L

    @Test
    fun `sem nenhum candidato nao ha proximo aviso`() {
        assertNull(NextAppAlarms.earliest(task = null, routine = null, summary = null))
    }

    @Test
    fun `um candidato so e ele mesmo, com o tipo`() {
        assertEquals(NextAppAlarm(task, AppAlarmKind.TASK), NextAppAlarms.earliest(task, null, null))
        assertEquals(NextAppAlarm(routine, AppAlarmKind.ROUTINE), NextAppAlarms.earliest(null, routine, null))
        assertEquals(NextAppAlarm(summary, AppAlarmKind.SUMMARY), NextAppAlarms.earliest(null, null, summary))
    }

    @Test
    fun `dois candidatos ganha o menor em cada par`() {
        assertEquals(NextAppAlarm(routine, AppAlarmKind.ROUTINE), NextAppAlarms.earliest(task, routine, null))
        assertEquals(NextAppAlarm(summary, AppAlarmKind.SUMMARY), NextAppAlarms.earliest(task, null, summary))
        assertEquals(NextAppAlarm(summary, AppAlarmKind.SUMMARY), NextAppAlarms.earliest(null, routine, summary))
        // O menor pode ser qualquer um, não depende da posição.
        assertEquals(NextAppAlarm(500L, AppAlarmKind.TASK), NextAppAlarms.earliest(500L, routine, null))
    }

    @Test
    fun `tres candidatos ganha o menor`() {
        assertEquals(NextAppAlarm(summary, AppAlarmKind.SUMMARY), NextAppAlarms.earliest(task, routine, summary))
        assertEquals(NextAppAlarm(500L, AppAlarmKind.TASK), NextAppAlarms.earliest(500L, routine, summary))
        assertEquals(NextAppAlarm(1_500L, AppAlarmKind.ROUTINE), NextAppAlarms.earliest(task, 1_500L, 1_600L))
    }

    @Test
    fun `empate no mesmo instante fica com tarefa, depois rotina, depois resumo`() {
        assertEquals(NextAppAlarm(1_000L, AppAlarmKind.TASK), NextAppAlarms.earliest(1_000L, 1_000L, 1_000L))
        assertEquals(NextAppAlarm(1_000L, AppAlarmKind.ROUTINE), NextAppAlarms.earliest(null, 1_000L, 1_000L))
    }
}
