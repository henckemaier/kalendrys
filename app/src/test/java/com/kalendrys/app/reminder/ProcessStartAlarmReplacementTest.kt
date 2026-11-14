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
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.summary.DailyReceiver
import com.kalendrys.app.summary.DailyScheduler
import com.kalendrys.app.testutil.FakeRoutineNotifications
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * ADR-0003, item 4.3: investigação do "pulo" do aviso de bloco e do resumo diário no início do
 * processo. Usa o [RoutineAlarms] e o [DailyScheduler] REAIS (mesmo PendingIntent e requestCode da
 * produção) sobre o [ShadowAlarmManager], com a permissão de alarme exato negada (caminho
 * `setAndAllowWhileIdle`, que o sistema pode atrasar).
 *
 * Cenário: o alarme de 9:00 (bloco) / 8:00 (resumo) ainda está pendente 3 minutos depois do
 * horário, e o processo sobe nesse momento ([com.kalendrys.app.KalendrysApp.onCreate] chama
 * `routineRepository.scheduleNextAlarm()`; `keepDailyAlarmsInSync` chama `dailyScheduler.scheduleAll`).
 *
 * Os testes PASSAM documentando o comportamento ATUAL (defeito confirmado): o reagendamento troca o
 * alarme pendente pelo seguinte e o aviso atrasado nunca sai. Quando a correção entrar, inverter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProcessStartAlarmReplacementTest {

    private lateinit var context: Context
    private lateinit var alarmManager: ShadowAlarmManager
    private lateinit var db: AppDatabase
    private lateinit var routineRepository: RoutineRepository
    private val zone: ZoneId = ZoneId.systemDefault()

    // Segunda-feira no futuro: nada depende do relógio real do teste.
    private val day: LocalDate = LocalDate.of(2030, 1, 7)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            RoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    // --- (a) Aviso de bloco de rotina -----------------------------------------------------------

    @Test
    fun `hoje o scheduleNextAlarm do inicio do processo substitui o aviso de bloco pendente`() = runTest {
        insertBlocks()
        routineRepository.scheduleNextAlarm(now = day.atTime(8, 50))
        val before = pendingRoutineAlarm()
        assertEquals(millis(day.atTime(9, 0)), before.triggerAtMs)
        assertEquals(9 * 60, before.minute)

        // Processo sobe às 9:03 com o alarme das 9:00 ainda não entregue.
        routineRepository.scheduleNextAlarm(now = day.atTime(9, 3))

        val after = pendingRoutineAlarm()
        assertEquals("um único alarme de rotina (mesmo PendingIntent)", 1, routineAlarms().size)
        assertEquals("o alarme das 9:00 foi trocado pelo das 10:00", millis(day.atTime(10, 0)), after.triggerAtMs)
        assertEquals(10 * 60, after.minute)
        assertEquals(day.toEpochDay(), after.epochDay)
    }

    @Test
    fun `hoje o alarme que substituiu nao entrega o bloco das 9h, que se perde`() = runTest {
        insertBlocks()
        routineRepository.scheduleNextAlarm(now = day.atTime(8, 50))
        routineRepository.scheduleNextAlarm(now = day.atTime(9, 3))
        val replacement = pendingRoutineAlarm()

        // O alarme que sobrou (10:00) toca na hora: onAlarm só olha [10:00, agora].
        val due = routineRepository.onAlarm(replacement.epochDay, replacement.minute, now = day.atTime(10, 0))

        assertEquals(listOf("Bloco 10h"), due.map { it.title })
    }

    @Test
    fun `controle - sem reagendamento, o alarme das 9h atrasado ainda entrega o bloco`() = runTest {
        insertBlocks()
        routineRepository.scheduleNextAlarm(now = day.atTime(8, 50))
        val original = pendingRoutineAlarm()

        // Mesmo alarme, entregue com 3 minutos de atraso: a tolerância do onAlarm cobre.
        val due = routineRepository.onAlarm(original.epochDay, original.minute, now = day.atTime(9, 3))

        assertEquals(listOf("Bloco 9h"), due.map { it.title })
    }

    // --- (b) Resumo diário -----------------------------------------------------------------------

    @Test
    fun `hoje o scheduleAll do inicio do processo substitui o resumo diario pendente`() {
        val scheduler = DailyScheduler(context)
        val settings = AppSettings(summaryEnabled = true, summaryTime = LocalTime.of(8, 0))
        scheduler.scheduleAll(settings, now = day.atTime(7, 50))
        assertEquals(millis(day.atTime(8, 0)), summaryAlarm()?.triggerAtMs)

        // Processo sobe às 8:03 com o resumo das 8:00 ainda não entregue.
        scheduler.scheduleAll(settings, now = day.atTime(8, 3))

        assertEquals("um único alarme de resumo", 1, alarmsWithAction(DailyReceiver.ACTION_SUMMARY).size)
        assertEquals(
            "o resumo de hoje foi trocado pelo de amanhã; o DailyReceiver não sabe do de hoje",
            millis(day.plusDays(1).atTime(8, 0)),
            summaryAlarm()?.triggerAtMs,
        )
    }

    @Test
    fun `controle - resumo desligado nao deixa alarme pendente`() {
        val scheduler = DailyScheduler(context)
        scheduler.scheduleAll(AppSettings(summaryEnabled = true), now = day.atTime(7, 50))
        scheduler.scheduleAll(AppSettings(summaryEnabled = false), now = day.atTime(7, 51))

        assertNull(summaryAlarm())
    }

    // --- Apoio ----------------------------------------------------------------------------------

    private suspend fun insertBlocks() {
        // createdAt = 0: a rotina já existia no dia do cenário.
        routineRepository.add(Routine(title = "Bloco 9h", startMinute = 9 * 60, createdAt = 0L))
        routineRepository.add(Routine(title = "Bloco 10h", startMinute = 10 * 60, createdAt = 0L))
    }

    private data class RoutineAlarm(val triggerAtMs: Long, val epochDay: Long, val minute: Int)

    private fun routineAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmsWithAction(RoutineAlarmReceiver.ACTION_START)

    private fun pendingRoutineAlarm(): RoutineAlarm {
        val alarm = routineAlarms().single()
        val intent = savedIntent(checkNotNull(alarm.operation))
        return RoutineAlarm(
            triggerAtMs = alarm.triggerAtMs,
            epochDay = intent.getLongExtra(RoutineAlarmReceiver.EXTRA_EPOCH_DAY, -1L),
            minute = intent.getIntExtra(RoutineAlarmReceiver.EXTRA_MINUTE, -1),
        )
    }

    private fun summaryAlarm(): ShadowAlarmManager.ScheduledAlarm? =
        alarmsWithAction(DailyReceiver.ACTION_SUMMARY).singleOrNull()

    private fun alarmsWithAction(action: String): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmManager.scheduledAlarms.filter { alarm ->
            alarm.operation?.let { savedIntent(it).action } == action
        }

    private fun savedIntent(operation: PendingIntent): Intent = shadowOf(operation).savedIntent

    private fun millis(at: LocalDateTime): Long = at.atZone(zone).toInstant().toEpochMilli()
}
