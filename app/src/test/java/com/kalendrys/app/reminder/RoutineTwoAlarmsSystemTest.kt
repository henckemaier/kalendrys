package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * v1.9.1 (E5-B, ADR-0003 8.4): os dois alarmes de rotina no AlarmManager (Robolectric), com o
 * [RoutineAlarms] e o [RoutineRepository] REAIS e o fuso do sistema, como na produção
 * (`zone = ZoneId.systemDefault()` dos dois lados).
 *
 * - PendingIntents distintos e reagendar sem duplicar;
 * - nulo de cada lado cancela só o seu;
 * - atualização por cima da v1.9.0: o ROUTINE_START silencioso (RTC) deixado no bloco sem aviso é
 *   substituído pelo despertador da que avisa, sem alarme órfão;
 * - troca de fuso do sistema: os horários saem no relógio local do fuso novo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineTwoAlarmsSystemTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var db: AppDatabase
    private lateinit var repository: RoutineRepository
    private lateinit var originalZone: TimeZone

    private val delivered = mutableListOf<String>()
    private val day: LocalDate = LocalDate.of(2030, 1, 7)

    @Before
    fun setup() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            RoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            deliverDueBlocks = { routines, _ -> delivered += routines.map { it.title } },
            mark = InMemoryRoutineCatchUpMark(),
            // Como na produção: o fuso lido a cada chamada, o mesmo que o RoutineAlarms usa.
            zone = { ZoneId.systemDefault() },
            clock = { millis(day.atTime(8, 0)) },
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(originalZone)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    // --- Apoio ----------------------------------------------------------------------------------

    private fun millis(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun scheduled(): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms

    private fun withAction(action: String): List<ShadowAlarmManager.ScheduledAlarm> =
        scheduled().filter { alarm -> alarm.operation?.let { shadowOf(it).savedIntent.action } == action }

    private fun remind(): ShadowAlarmManager.ScheduledAlarm? = withAction(RoutineAlarmReceiver.ACTION_START).singleOrNull()

    private fun refresh(): ShadowAlarmManager.ScheduledAlarm? = withAction(RoutineAlarmReceiver.ACTION_REFRESH).singleOrNull()

    /** Minuto local do horário do alarme, no fuso do sistema (desde a v1.10.0 o alarme não leva extras). */
    private fun minuteExtra(alarm: ShadowAlarmManager.ScheduledAlarm): Int =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(alarm.triggerAtTime), ZoneId.systemDefault())
            .let { it.hour * 60 + it.minute }

    private suspend fun silentThenReminding() {
        repository.add(Routine(title = "Leitura", startMinute = 8 * 60 + 30, remind = false, createdAt = 0L))
        repository.add(Routine(title = "Treino", startMinute = 9 * 60, createdAt = 0L))
    }

    /** O alarme único da v1.9.0, como ela o deixava num bloco sem aviso: ROUTINE_START, requestCode 0, RTC. */
    private fun placeV190SilentStart(at: LocalDateTime) {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(RoutineAlarmReceiver.ACTION_START)
                // Os extras da v1.9.x, com as chaves de então.
                .putExtra("extra_epoch_day", at.toLocalDate().toEpochDay())
                .putExtra("extra_minute", at.hour * 60 + at.minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.set(AlarmManager.RTC, millis(at), operation)
    }

    // --- PendingIntents e cancelamento ----------------------------------------------------------

    @Test
    fun `os dois PendingIntents sao distintos e reagendar varias vezes nao duplica`() {
        val alarms = RoutineAlarms(context)
        repeat(3) { alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = millis(day.atTime(8, 30))) }

        assertEquals("um de cada, sem duplicar", 2, scheduled().size)
        assertNotEquals(remind()!!.operation, refresh()!!.operation)

        // Horários trocados: cada um no seu lugar, ainda dois.
        alarms.schedule(remindAtMillis = millis(day.atTime(10, 0)), refreshAtMillis = millis(day.atTime(9, 30)))
        assertEquals(2, scheduled().size)
        assertEquals(millis(day.atTime(10, 0)), remind()!!.triggerAtTime)
        assertEquals(10 * 60, minuteExtra(remind()!!))
        assertEquals(millis(day.atTime(9, 30)), refresh()!!.triggerAtTime)
        assertEquals(9 * 60 + 30, minuteExtra(refresh()!!))
    }

    @Test
    fun `aviso nulo cancela so o aviso e mantem o dos widgets`() {
        val alarms = RoutineAlarms(context)
        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = millis(day.atTime(8, 30)))

        alarms.schedule(remindAtMillis = null, refreshAtMillis = millis(day.atTime(8, 30)))

        assertNull(remind())
        assertEquals(millis(day.atTime(8, 30)), refresh()!!.triggerAtTime)
        assertEquals(AlarmManager.RTC, refresh()!!.type)
    }

    @Test
    fun `widgets nulo cancela so o dos widgets e mantem o aviso`() {
        val alarms = RoutineAlarms(context)
        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = millis(day.atTime(8, 30)))

        alarms.schedule(remindAtMillis = millis(day.atTime(9, 0)), refreshAtMillis = null)

        assertNull(refresh())
        assertNotNull(remind()!!.alarmClockInfo)
        assertEquals(millis(day.atTime(9, 0)), remind()!!.triggerAtTime)
    }

    // --- Atualização por cima da v1.9.0 (8.4, item 5) -------------------------------------------

    @Test
    fun `por cima da v1_9_0 - o ROUTINE_START silencioso das 8h30 vira o despertador das 9h, sem orfao`() = runTest {
        placeV190SilentStart(day.atTime(8, 30))
        db.routineDao().insert(Routine(title = "Leitura", startMinute = 8 * 60 + 30, remind = false, createdAt = 0L))
        db.routineDao().insert(Routine(title = "Treino", startMinute = 9 * 60, createdAt = 0L))

        // MY_PACKAGE_REPLACED / início do processo: o catch-up reagenda os dois.
        repository.catchUp(nowOverride = millis(day.atTime(8, 0)))

        val start = withAction(RoutineAlarmReceiver.ACTION_START)
        assertEquals("o ROUTINE_START antigo foi substituído, não duplicado", 1, start.size)
        assertEquals(millis(day.atTime(9, 0)), start.single().triggerAtTime)
        assertNotNull("agora é despertador", start.single().alarmClockInfo)
        assertEquals(9 * 60, minuteExtra(start.single()))
        assertEquals(millis(day.atTime(8, 30)), refresh()!!.triggerAtTime)
        assertEquals(2, scheduled().size)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `por cima da v1_9_0 sem permissao de alarme exato - o aviso acorda o aparelho`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        placeV190SilentStart(day.atTime(8, 30))
        db.routineDao().insert(Routine(title = "Leitura", startMinute = 8 * 60 + 30, remind = false, createdAt = 0L))
        db.routineDao().insert(Routine(title = "Treino", startMinute = 9 * 60, createdAt = 0L))

        repository.catchUp(nowOverride = millis(day.atTime(8, 0)))

        val start = withAction(RoutineAlarmReceiver.ACTION_START).single()
        assertEquals(millis(day.atTime(9, 0)), start.triggerAtTime)
        assertEquals("inexato, mas acorda", AlarmManager.RTC_WAKEUP, start.type)
    }

    @Test
    fun `por cima da v1_9_0 so com silenciosas - o ROUTINE_START antigo e cancelado`() = runTest {
        placeV190SilentStart(day.atTime(8, 30))
        db.routineDao().insert(Routine(title = "Leitura", startMinute = 8 * 60 + 30, remind = false, createdAt = 0L))

        repository.catchUp(nowOverride = millis(day.atTime(8, 0)))

        assertTrue("sem rotina que avise, sem ROUTINE_START", withAction(RoutineAlarmReceiver.ACTION_START).isEmpty())
        assertEquals(millis(day.atTime(8, 30)), refresh()!!.triggerAtTime)
    }

    @Test
    fun `por cima da v1_9_0 com o aviso pendente das 9h - o aviso sai uma vez so`() = runTest {
        // O processo sobe às 9:02 com o alarme antigo ainda pendente: o catch-up entrega e só então troca.
        silentThenReminding()
        placeV190SilentStart(day.atTime(8, 30))

        repository.catchUp(nowOverride = millis(day.atTime(9, 2)))
        repository.catchUp(nowOverride = millis(day.atTime(9, 3))) // o alarme novo, se chegasse junto

        assertEquals(listOf("Treino"), delivered)
        assertEquals(millis(day.plusDays(1).atTime(9, 0)), remind()!!.triggerAtTime)
        assertEquals(millis(day.plusDays(1).atTime(8, 30)), refresh()!!.triggerAtTime)
    }

    // --- Troca de fuso do sistema ---------------------------------------------------------------

    @Test
    fun `troca de fuso do sistema - os dois alarmes no relogio local do fuso novo`() = runTest {
        silentThenReminding()
        val instant = millis(day.atTime(8, 0)) // 8:00 em São Paulo = 11:00 em Lisboa
        assertEquals(millis(day.atTime(9, 0)), remind()!!.triggerAtTime)

        // TIMEZONE_CHANGED: BootReceiver -> catchUp().
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
        repository.catchUp(nowOverride = instant)

        val tomorrow = day.plusDays(1)
        assertEquals(millis(tomorrow.atTime(9, 0)), remind()!!.triggerAtTime)
        assertEquals(millis(tomorrow.atTime(8, 30)), refresh()!!.triggerAtTime)
        assertEquals(9 * 60, minuteExtra(remind()!!))
        assertEquals("diagnóstico = sistema", remind()!!.triggerAtTime, repository.nextRemindingStart(instant))
        assertTrue(delivered.isEmpty())
    }
}
