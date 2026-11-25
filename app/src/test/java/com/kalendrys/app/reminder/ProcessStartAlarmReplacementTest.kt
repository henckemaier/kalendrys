package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.summary.DailyReceiver
import com.kalendrys.app.summary.DailyScheduler
import com.kalendrys.app.summary.DailySummaryCatchUp
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * `routineRepository.catchUp()`; `keepDailyAlarmsInSync`, na primeira emissão, o catch-up do resumo).
 *
 * (a) Aviso de bloco: CORRIGIDO (4.3.1). O início do processo passa pelo
 * [RoutineRepository.catchUp], que entrega o bloco atrasado antes de substituir o alarme pendente.
 *
 * (b) Resumo: CORRIGIDO (4.3.2). O início do processo passa pelo
 * [com.kalendrys.app.summary.DailySummaryCatchUp], que entrega o resumo atrasado antes de trocar
 * o alarme pendente pelo de amanhã.
 *
 * (c) v1.9.1 (E5-B): DOIS alarmes de rotina. O do AVISO (o mesmo PendingIntent da v1.9.0) é sempre
 * o próximo início que avisa, mesmo com um bloco sem aviso antes; o silencioso dos widgets
 * ([RoutineAlarmReceiver.ACTION_REFRESH]) só existe nesse caso. Até a v1.9.0 o alarme único ficava no
 * bloco sem aviso (RTC, não acorda) e o aviso só era agendado quando ele disparava.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProcessStartAlarmReplacementTest {

    private lateinit var context: Context
    private lateinit var alarmManager: ShadowAlarmManager
    private lateinit var db: AppDatabase
    private lateinit var routineRepository: RoutineRepository
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Títulos dos blocos avisados pelo catch-up, na ordem. */
    private val delivered = mutableListOf<String>()

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
            deliverDueBlocks = { routines, _ -> delivered += routines.map { it.title } },
            mark = InMemoryRoutineCatchUpMark(),
            zone = { zone },
            clock = { millis(day.atTime(8, 0)) },
        )
    }

    @After
    fun tearDown() {
        db.close()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    // --- (a) Aviso de bloco de rotina -----------------------------------------------------------

    @Test
    fun `o catchUp do inicio do processo entrega o bloco das 9h antes de substituir o alarme pendente`() = runTest {
        insertBlocks()
        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 50)))
        val before = pendingRoutineAlarm()
        assertEquals(millis(day.atTime(9, 0)), before.triggerAtMs)
        assertEquals(9 * 60, before.minute)

        // Processo sobe às 9:03 com o alarme das 9:00 ainda não entregue.
        routineRepository.catchUp(nowOverride = millis(day.atTime(9, 3)))

        assertEquals("o bloco das 9:00 é avisado na hora", listOf("Bloco 9h"), delivered)
        // Só depois da entrega o alarme é trocado pelo próximo início: substituir já não perde nada.
        val after = pendingRoutineAlarm()
        assertEquals("um único alarme de aviso (mesmo PendingIntent)", 1, routineAlarms().size)
        assertEquals(millis(day.atTime(10, 0)), after.triggerAtMs)
        assertEquals(10 * 60, after.minute)
        assertEquals(day.toEpochDay(), after.epochDay)
        assertTrue("o próximo início já é o do aviso: sem o silencioso", refreshAlarms().isEmpty())
    }

    @Test
    fun `o alarme que substituiu entrega o bloco das 10h sem repetir o das 9h`() = runTest {
        insertBlocks()
        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 50)))
        routineRepository.catchUp(nowOverride = millis(day.atTime(9, 3)))

        // O alarme que sobrou (10:00) toca na hora.
        routineRepository.catchUp(nowOverride = millis(day.atTime(10, 0)))

        assertEquals(listOf("Bloco 9h", "Bloco 10h"), delivered)
    }

    @Test
    fun `o alarme das 9h atrasado entrega o bloco uma vez, e o inicio do processo depois nao repete`() = runTest {
        insertBlocks()
        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 50)))

        // Mesmo alarme, entregue com 3 minutos de atraso; logo depois o processo "sobe" de novo.
        routineRepository.catchUp(nowOverride = millis(day.atTime(9, 3)))
        routineRepository.catchUp(nowOverride = millis(day.atTime(9, 4)))

        assertEquals(listOf("Bloco 9h"), delivered)
    }

    // --- (c) E5-B: bloco sem aviso antes do aviso -------------------------------------------------

    @Test
    fun `E5-B - bloco sem aviso antes do aviso agenda o aviso no horario dele e o silencioso no bloco`() = runTest {
        insertSilentThenReminding()

        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 0)))

        // O aviso das 9:00 já está no AlarmManager: não depende do alarme silencioso das 8:30.
        val remind = pendingRoutineAlarm()
        assertEquals(millis(day.atTime(9, 0)), remind.triggerAtMs)
        assertEquals(9 * 60, remind.minute)
        val refresh = refreshAlarms().single()
        assertEquals(millis(day.atTime(8, 30)), refresh.triggerAtMs)
        assertEquals("o dos widgets não acorda o aparelho", AlarmManager.RTC, refresh.type)
    }

    @Test
    fun `E5-B - com o aparelho dormindo o silencioso nao toca e o aviso das 9h sai pelo proprio alarme`() = runTest {
        insertSilentThenReminding()
        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 0)))

        // 8:30 passa sem nada (RTC não acorda); às 9:00 o despertador do aviso acorda o app.
        routineRepository.catchUp(nowOverride = millis(day.atTime(9, 0)))

        assertEquals(listOf("Aviso 9h"), delivered)
        // Reagendados os dois, para amanhã: o silencioso de novo antes do aviso.
        assertEquals(millis(day.plusDays(1).atTime(9, 0)), pendingRoutineAlarm().triggerAtMs)
        assertEquals(millis(day.plusDays(1).atTime(8, 30)), refreshAlarms().single().triggerAtMs)
    }

    @Test
    fun `E5-B - o alarme silencioso, quando toca, so redesenha e mantem o aviso`() = runTest {
        insertSilentThenReminding()
        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 0)))

        // O silencioso das 8:30 tocou (aparelho acordado): nada a avisar, o aviso das 9:00 continua.
        routineRepository.catchUp(nowOverride = millis(day.atTime(8, 30)))

        assertTrue(delivered.isEmpty())
        assertEquals(millis(day.atTime(9, 0)), pendingRoutineAlarm().triggerAtMs)
        assertTrue("sem outro bloco sem aviso antes das 9:00", refreshAlarms().isEmpty())
    }

    // --- (b) Resumo diário -----------------------------------------------------------------------

    @Test
    fun `o catch-up do resumo no inicio do processo entrega o de hoje antes de trocar o alarme pelo de amanha`() = runTest {
        val scheduler = DailyScheduler(context)
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "summary-${System.nanoTime()}.preferences_pb") },
        )
        var shown = 0
        val catchUp = DailySummaryCatchUp(
            settings = settings,
            schedule = { s, now -> scheduler.scheduleAll(s, now) },
            show = { shown++ },
            zone = { zone },
        )
        catchUp.run(millis(day.atTime(7, 50))) // resumo padrão às 8:00, ligado
        assertEquals(millis(day.atTime(8, 0)), summaryAlarm()?.triggerAtMs)

        // Processo sobe às 8:03 com o resumo das 8:00 ainda não entregue.
        catchUp.run(millis(day.atTime(8, 3)))

        assertEquals("o resumo de hoje sai na hora", 1, shown)
        // Só depois da entrega o alarme é trocado pelo de amanhã: substituir já não perde nada.
        assertEquals("um único alarme de resumo", 1, alarmsWithAction(DailyReceiver.ACTION_SUMMARY).size)
        assertEquals(millis(day.plusDays(1).atTime(8, 0)), summaryAlarm()?.triggerAtMs)

        // O alarme atrasado (ou outro ponto de entrada) chega depois: janela vazia, não repete.
        catchUp.run(millis(day.atTime(8, 5)))
        assertEquals(1, shown)
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

    /** Um bloco que não avisa às 8:30 antes do que avisa às 9:00 (o cenário da E5-B). */
    private suspend fun insertSilentThenReminding() {
        routineRepository.add(Routine(title = "Leitura 8h30", startMinute = 8 * 60 + 30, remind = false, createdAt = 0L))
        routineRepository.add(Routine(title = "Aviso 9h", startMinute = 9 * 60, createdAt = 0L))
    }

    /** Dia e minuto locais tirados do próprio horário do alarme (desde a v1.10.0 ele não leva extras). */
    private data class RoutineAlarm(val triggerAtMs: Long, val epochDay: Long, val minute: Int)

    private fun routineAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmsWithAction(RoutineAlarmReceiver.ACTION_START)

    private fun refreshAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmsWithAction(RoutineAlarmReceiver.ACTION_REFRESH)

    private fun pendingRoutineAlarm(): RoutineAlarm {
        val alarm = routineAlarms().single()
        val local = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(alarm.triggerAtMs), zone)
        return RoutineAlarm(
            triggerAtMs = alarm.triggerAtMs,
            epochDay = local.toLocalDate().toEpochDay(),
            minute = local.hour * 60 + local.minute,
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
