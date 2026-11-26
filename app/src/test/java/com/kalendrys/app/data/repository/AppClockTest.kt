package com.kalendrys.app.data.repository

import com.kalendrys.app.testutil.FakeAppClock
import com.kalendrys.app.testutil.clockOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O relógio único (v1.10.0, ADR-0002 16.3) por ele mesmo: o laço da meia-noite do [AppClock.dates]
 * DE PRODUÇÃO (o [FakeAppClock] substitui o [AppClock.dates] pelo dele; os testes de virada de dia das
 * telas exercitam o falso, não este laço), as extensões e o limite conhecido N2.
 *
 * O "agora" segue o tempo virtual do teste ([clockOf] com `currentTime`), então o `delay` do laço é
 * medido de verdade, sem esperar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppClockTest {

    private var zone: ZoneId = SAO_PAULO

    /** Relógio de produção (o [AppClock.dates] padrão) sobre o tempo virtual, começando em [start] no fuso [zone]. */
    private fun TestScope.virtualClock(start: LocalDateTime): AppClock {
        val base = start.atZone(zone).toInstant().toEpochMilli()
        return clockOf(zone = { zone }) { base + testScheduler.currentTime }
    }

    private fun TestScope.collectDates(clock: AppClock): MutableList<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch { clock.dates().toList(dates) }
        runCurrent()
        return dates
    }

    // --- O laço da meia-noite -------------------------------------------------------------------

    @Test
    fun `dates emite hoje ao comecar e o dia novo logo depois da meia-noite, nao antes`() = runTest {
        val day = LocalDate.of(2030, 1, 7)
        val dates = collectDates(virtualClock(day.atTime(22, 0)))
        assertEquals(listOf(day), dates)

        // 2 h depois é meia-noite em ponto; o laço espera a margem de 1 s.
        advanceTimeBy(Duration.ofHours(2).toMillis())
        runCurrent()
        assertEquals("ainda na margem da meia-noite", listOf(day), dates)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(day, day.plusDays(1)), dates)

        // O dia seguinte inteiro sem outra emissão; a próxima é na outra meia-noite.
        advanceTimeBy(Duration.ofHours(23).toMillis())
        runCurrent()
        assertEquals(listOf(day, day.plusDays(1)), dates)
        advanceTimeBy(Duration.ofHours(1).toMillis())
        runCurrent()
        assertEquals(listOf(day, day.plusDays(1), day.plusDays(2)), dates)
    }

    @Test
    fun `dia de 23 horas (inicio do horario de verao) - a virada e na meia-noite local`() = runTest {
        zone = NEW_YORK
        val springForward = LocalDate.of(2030, 3, 10) // 2:00 vira 3:00
        val dates = collectDates(virtualClock(springForward.atTime(0, 30)))

        // O dia tem 23 h: da 0:30 à meia-noite seguinte faltam 22h30 reais (23h30 de relógio de parede).
        advanceTimeBy(Duration.ofMinutes(22 * 60 + 30).toMillis())
        runCurrent()
        assertEquals(listOf(springForward), dates)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(springForward, springForward.plusDays(1)), dates)
    }

    @Test
    fun `dia de 25 horas (fim do horario de verao) - nao vira o dia uma hora antes`() = runTest {
        zone = NEW_YORK
        val fallBack = LocalDate.of(2030, 11, 3) // 2:00 volta para 1:00
        val dates = collectDates(virtualClock(fallBack.atTime(0, 30)))

        // O dia tem 25 h: 23h30 reais depois da 0:30 são só 23:00 locais.
        advanceTimeBy(Duration.ofMinutes(23 * 60 + 30).toMillis())
        runCurrent()
        assertEquals("às 23:00 locais ainda é o mesmo dia", listOf(fallBack), dates)
        advanceTimeBy(Duration.ofHours(1).toMillis() + 1_000L)
        runCurrent()
        assertEquals(listOf(fallBack, fallBack.plusDays(1)), dates)
    }

    // --- N2: fuso trocado com a coleta em andamento ---------------------------------------------

    @Test
    fun `N2 - trocar o fuso com a coleta em andamento nao reemite ate a meia-noite medida no fuso antigo`() = runTest {
        val day = LocalDate.of(2030, 1, 7)
        val clock = virtualClock(day.atTime(22, 0)) // 22:00 em São Paulo = 01:00 do dia 8 em Lisboa
        val dates = collectDates(clock)

        zone = LISBON
        assertEquals("o relógio já diz o dia novo", day.plusDays(1), clock.today())
        advanceTimeBy(Duration.ofHours(2).toMillis()) // 03:00 em Lisboa
        runCurrent()
        assertEquals("limite conhecido (16.3): o fluxo segue no dia antigo", listOf(day), dates)

        // Só quando acaba a espera medida em São Paulo (meia-noite de lá + 1 s).
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(day, day.plusDays(1)), dates)
    }

    @Test
    fun `N2 - recomecar a coleta depois da troca de fuso le o dia novo na hora`() = runTest {
        val day = LocalDate.of(2030, 1, 7)
        val clock = virtualClock(day.atTime(22, 0))
        assertEquals(day, clock.dates().first())

        zone = LISBON

        assertEquals(day.plusDays(1), clock.dates().first())
    }

    @Test
    fun `N2 - o relogio de teste tem o mesmo limite - trocar so o fuso nao emite`() = runTest {
        val day = LocalDate.of(2030, 1, 7)
        val fake = FakeAppClock.at(day.atTime(22, 0), SAO_PAULO)
        val dates = collectDates(fake)

        fake.zoneId = LISBON
        runCurrent()
        assertEquals(listOf(day), dates)

        // O próximo avanço do instante lê o fuso novo.
        fake.advanceBy(Duration.ofMinutes(1))
        runCurrent()
        assertEquals(listOf(day, day.plusDays(1)), dates)
    }

    // --- Extensões ------------------------------------------------------------------------------

    @Test
    fun `extensoes saem dos dois primitivos e acompanham o fuso`() {
        val instant = LocalDateTime.of(2030, 1, 7, 22, 15).atZone(SAO_PAULO).toInstant().toEpochMilli()
        val clock = clockOf(zone = { zone }) { instant }

        assertEquals(LocalDate.of(2030, 1, 7), clock.today())
        assertEquals(LocalTime.of(22, 15), clock.timeOfDay())
        assertEquals(LocalDateTime.of(2030, 1, 7, 22, 15), clock.nowLocal())
        assertEquals(instant, clock.now().toInstant().toEpochMilli())

        zone = LISBON
        assertEquals(LocalDate.of(2030, 1, 8), clock.today())
        assertEquals(LocalTime.of(1, 15), clock.timeOfDay())
        assertEquals("o instante não muda com o fuso", instant, clock.now().toInstant().toEpochMilli())
    }

    @Test
    fun `relogio de teste - avancar antes da meia-noite nao emite, cruzar emite uma vez`() = runTest {
        val day = LocalDate.of(2030, 1, 7)
        val fake = FakeAppClock.at(day.atTime(23, 0), SAO_PAULO)
        val dates = collectDates(fake)

        fake.setLocal(day.atTime(23, 59, 59))
        runCurrent()
        assertEquals(listOf(day), dates)

        fake.setLocal(day.plusDays(1).atTime(0, 0))
        fake.advanceBy(Duration.ofHours(3))
        runCurrent()
        assertEquals(listOf(day, day.plusDays(1)), dates)
        assertEquals(fake.today(), dates.last())
    }

    private companion object {
        val SAO_PAULO: ZoneId = ZoneId.of("America/Sao_Paulo")
        val LISBON: ZoneId = ZoneId.of("Europe/Lisbon")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}
