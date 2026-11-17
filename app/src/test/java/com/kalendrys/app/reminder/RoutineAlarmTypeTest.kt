package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * E1 (v1.8.0, ADR-0002 14.3): o alarme do próximo início de rotina NÃO é pulado (ele redesenha os
 * widgets); só muda de tipo. Exato (despertador) se alguma rotina que avisa ainda não foi concluída
 * no dia; silencioso (RTC, sem acordar) se todas as que avisam já foram.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineAlarmTypeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val alarms = RoutineAlarms(context)
    private val after = LocalDate.now().atStartOfDay().minusMinutes(1)

    @org.junit.Before
    fun grantExact() {
        // No Robolectric a permissão "Alarmes e lembretes" vem negada; aqui exercita o caminho exato.
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun routine(id: Long, remind: Boolean) = Routine(id = id, title = "R$id", startMinute = 480, remind = remind)

    private fun marked() = shadowOf(alarmManager).scheduledAlarms.single()

    @Test
    fun `rotina que avisa e nao foi concluida marca alarme exato`() {
        alarms.scheduleNext(listOf(routine(1, remind = true)), after, emptyMap(), doneOnStartDay = emptySet())
        assertNotNull(marked().alarmClockInfo)
    }

    @Test
    fun `todas as que avisam concluidas no dia marca alarme silencioso no mesmo horario`() {
        alarms.scheduleNext(listOf(routine(1, remind = true)), after, emptyMap(), doneOnStartDay = setOf(1L))
        val alarm = marked()
        assertNull(alarm.alarmClockInfo)
        assertEquals(AlarmManager.RTC, alarm.type)
        val expected = LocalDate.now().atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, alarm.triggerAtTime)
    }

    @Test
    fun `uma que avisa pendente no mesmo inicio mantem o alarme exato`() {
        val routines = listOf(routine(1, remind = true), routine(2, remind = true))
        alarms.scheduleNext(routines, after, emptyMap(), doneOnStartDay = setOf(1L))
        assertNotNull(marked().alarmClockInfo)
    }

    @Test
    fun `sem aviso continua silenciosa`() {
        alarms.scheduleNext(listOf(routine(1, remind = false)), after, emptyMap(), doneOnStartDay = emptySet())
        assertEquals(AlarmManager.RTC, marked().type)
    }
}
