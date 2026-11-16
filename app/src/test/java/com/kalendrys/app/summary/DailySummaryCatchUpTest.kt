package com.kalendrys.app.summary

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.data.settings.ThemeMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Catch-up do resumo diário (ADR-0003, 4.3.4): DataStore real em arquivo temporário; agendar e
 * mostrar registram num diário comum, para conferir a ordem entrega → agenda → marca.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DailySummaryCatchUpTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day = LocalDate.of(2030, 1, 7)

    private lateinit var settings: SettingsRepository
    private lateinit var catchUp: DailySummaryCatchUp
    private val log = mutableListOf<String>()
    private val scheduledAt = mutableListOf<LocalDateTime>()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "summary-${System.nanoTime()}.preferences_pb") },
        )
        catchUp = DailySummaryCatchUp(
            settings = settings,
            schedule = { _, now ->
                scheduledAt += now
                log += "schedule"
            },
            show = { log += "show" },
            zone = { zone },
        )
    }

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private suspend fun seenAt(millis: Long) {
        settings.setSummaryLastSeen(millis)
        log.clear()
        scheduledAt.clear()
    }

    private fun shows() = log.count { it == "show" }

    @Test
    fun `abrir 3 minutos depois do horario mostra uma vez e agenda o de amanha`() = runTest {
        seenAt(at(7, 50)) // resumo padrão às 8:00, ligado
        catchUp.run(at(8, 3))
        assertEquals(listOf("show", "schedule"), log)
        assertEquals(at(8, 3), settings.summaryLastSeenAt())
        // Agendado a partir das 8:03: o próximo 8:00 é o de amanhã.
        assertEquals(LocalDateTime.of(day, LocalTime.of(8, 3)), scheduledAt.single())

        catchUp.run(at(8, 9)) // o alarme atrasado chega depois
        assertEquals(1, shows())
    }

    @Test
    fun `primeira execucao so estabelece a marca`() = runTest {
        catchUp.run(at(8, 3))
        assertEquals(listOf("schedule"), log)
        assertEquals(at(8, 3), settings.summaryLastSeenAt())
    }

    @Test
    fun `trocar para horario ja passado nao mostra nada`() = runTest {
        seenAt(at(9, 50))
        settings.setSummaryTime(LocalTime.of(9, 30))
        catchUp.establishBaseline(settings.settings.first(), nowOverride = at(10))
        catchUp.run(at(10, 5))
        assertEquals(0, shows())
    }

    @Test
    fun `trocar para mais tarde no mesmo dia mostra de novo`() = runTest {
        seenAt(at(7, 50))
        catchUp.run(at(8, 0) + 500) // resumo das 8:00 na hora
        settings.setSummaryTime(LocalTime.of(9, 0))
        catchUp.establishBaseline(settings.settings.first(), nowOverride = at(8, 30))
        catchUp.run(at(9, 0) + 500)
        assertEquals(2, shows())
    }

    @Test
    fun `resumo desligado nao mostra`() = runTest {
        seenAt(at(7, 50))
        settings.setSummaryEnabled(false)
        catchUp.run(at(8, 3))
        assertEquals(listOf("schedule"), log)
    }

    @Test
    fun `mais de 60 minutos de atraso descarta e grava a marca`() = runTest {
        seenAt(at(7, 50))
        catchUp.run(at(9, 1))
        assertEquals(0, shows())
        assertEquals(at(9, 1), settings.summaryLastSeenAt())
    }

    @Test
    fun `restauro so agenda e nao entrega o resumo`() = runTest {
        seenAt(at(7, 50, day.minusDays(2)))
        catchUp.establishBaseline(nowOverride = at(8, 3))
        catchUp.run(at(8, 5))
        assertEquals(0, shows())
        assertEquals(listOf("schedule", "schedule"), log)
    }

    @Test
    fun `duas execucoes simultaneas mostram uma vez`() = runTest {
        seenAt(at(7, 50))
        withContext(Dispatchers.Default) {
            listOf(at(8, 3), at(8, 3) + 1, at(8, 3) + 2).map { async { catchUp.run(it) } }.awaitAll()
        }
        assertEquals(1, shows())
    }

    @Test
    fun `configuracao padrao e resumo ligado as 8`() = runTest {
        assertEquals(AppSettings(), settings.settings.first())
    }

    @Test
    fun `so mudancas do resumo chegam ao keepDailyAlarmsInSync`() = runTest {
        val eight = AppSettings(summaryEnabled = true, summaryTime = LocalTime.of(8, 0))
        val nine = eight.copy(summaryTime = LocalTime.of(9, 0))
        val off = nine.copy(summaryEnabled = false)
        val emitted = flowOf(eight, eight.copy(), nine, nine, off).summaryChanges().toList()
        assertEquals(listOf(eight, nine, off), emitted)
    }

    @Test
    fun `trocar tema e cor dinamica nao emite em summaryChanges`() = runTest {
        val eight = AppSettings(summaryEnabled = true, summaryTime = LocalTime.of(8, 0))
        val dark = eight.copy(themeMode = ThemeMode.DARK)
        val dynamic = dark.copy(dynamicColor = true)
        val light = dynamic.copy(themeMode = ThemeMode.LIGHT, dynamicColor = false)
        val emitted = flowOf(eight, dark, dynamic, light).summaryChanges().toList()
        // Só a primeira: a aparência não reagenda o resumo nem zera a marca dele (L14).
        assertEquals(listOf(eight), emitted)
    }

    @Test
    fun `resumo das 23h50 com catch-up as 0h10 nao mostra e agenda o das 23h50 do dia novo`() = runTest {
        settings.setSummaryTime(LocalTime.of(23, 50))
        seenAt(at(23, 40, day.minusDays(1)))
        catchUp.run(at(0, 10))
        assertEquals(listOf("schedule"), log)
        // Agendado a partir de 0:10: o próximo 23:50 é o do dia novo (DailyScheduler.nextDailyAt).
        assertEquals(LocalDateTime.of(day, LocalTime.of(0, 10)), scheduledAt.single())
        assertEquals(at(0, 10), settings.summaryLastSeenAt())
    }

    @Test
    fun `resumo das 23h40 com catch-up as 23h55 mostra`() = runTest {
        settings.setSummaryTime(LocalTime.of(23, 40))
        seenAt(at(23, 30))
        catchUp.run(at(23, 55))
        assertEquals(listOf("show", "schedule"), log)
    }
}
