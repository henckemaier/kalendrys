package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * v1.9.1 (E5-B): os dois alarmes das rotinas, cada um com o seu PendingIntent e o seu tipo. O
 * [RoutineAlarms] não calcula nada (os instantes vêm prontos do repositório, v1.10.0); aqui só o
 * tipo, a ação, o horário e o cancelamento de cada um.
 * - AVISO ([RoutineAlarmReceiver.ACTION_START], o mesmo da v1.9.0): despertador (exato, acorda).
 * - WIDGETS ([RoutineAlarmReceiver.ACTION_REFRESH]): silencioso (RTC, não acorda).
 *
 * Até a v1.9.0 era um alarme só, que virava RTC num início sem aviso (E1 da v1.8.0, ADR-0002 14.3);
 * com o aparelho dormindo, o aviso seguinte podia nunca ser agendado.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineAlarmTypeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val alarms = RoutineAlarms(context)
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.now().plusDays(1)

    @Before
    fun grantExact() {
        // No Robolectric a permissão "Alarmes e lembretes" vem negada; aqui exercita o caminho exato.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun restore() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun alarmFor(action: String): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(alarmManager).scheduledAlarms.singleOrNull { alarm ->
            alarm.operation?.let { shadowOf(it).savedIntent.action } == action
        }

    private fun remindAlarm() = alarmFor(RoutineAlarmReceiver.ACTION_START)

    private fun refreshAlarm() = alarmFor(RoutineAlarmReceiver.ACTION_REFRESH)

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `aviso e despertador no horario do aviso`() {
        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = null)

        val alarm = checkNotNull(remindAlarm())
        assertNotNull("despertador: acorda o aparelho", alarm.alarmClockInfo)
        assertEquals(millis(day.atTime(9, 0)), alarm.triggerAtTime)
        assertNull(refreshAlarm())
    }

    @Test
    fun `widgets e silencioso no horario do bloco sem aviso`() {
        alarms.schedule(remindAtMillis = null, refreshAtMillis = millis(day.atTime(8, 0)))

        val alarm = checkNotNull(refreshAlarm())
        assertNull(alarm.alarmClockInfo)
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(millis(day.atTime(8, 0)), alarm.triggerAtTime)
        assertNull(remindAlarm())
    }

    @Test
    fun `os dois convivem - o silencioso antes nao apaga nem atrasa o aviso`() {
        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = millis(day.atTime(8, 0)))

        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
        assertNotNull(remindAlarm()!!.alarmClockInfo)
        assertEquals(millis(day.atTime(9, 0)), remindAlarm()!!.triggerAtTime)
        assertEquals(AlarmManager.RTC, refreshAlarm()!!.type)
        assertEquals(millis(day.atTime(8, 0)), refreshAlarm()!!.triggerAtTime)
    }

    @Test
    fun `reagendar substitui cada um no seu lugar e o nulo cancela`() {
        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = millis(day.atTime(8, 0)))

        alarms.schedule(remindAtMillis = millis(day.atTime(10, 0)), refreshAtMillis = null)

        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        assertEquals(millis(day.atTime(10, 0)), remindAlarm()!!.triggerAtTime)
        assertNull("sem horário, o dos widgets é cancelado", refreshAlarm())

        alarms.schedule(remindAtMillis = null, refreshAtMillis = null)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `os alarmes nao levam extras - quem dispara so roda o catch-up`() {
        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = millis(day.atTime(8, 0)))

        for (alarm in shadowOf(alarmManager).scheduledAlarms) {
            assertNull(shadowOf(checkNotNull(alarm.operation)).savedIntent.extras)
        }
    }

    @Test
    fun `sem alarme exato o aviso segue agendado - caminho inexato`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = null)

        assertEquals(millis(day.atTime(9, 0)), remindAlarm()?.triggerAtTime)
    }
}
