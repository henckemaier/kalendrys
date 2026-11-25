package com.kalendrys.app.testutil

import com.kalendrys.app.data.repository.AppClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Relógio de teste (v1.10.0, ADR-0002 16.3, A1 do Esquadro): UM instante só, de onde saem agora, hoje
 * e o fluxo [dates]. Avançar o instante ([advanceTo], [advanceBy], [setLocal]) move o agora e, se
 * cruzou a meia-noite, emite o dia novo; hoje e agora nunca discordam. O fuso é lido a cada chamada.
 */
class FakeAppClock(startMillis: Long, zone: ZoneId = ZoneId.systemDefault()) : AppClock {

    private val instant = MutableStateFlow(startMillis)

    @Volatile
    var zoneId: ZoneId = zone

    override fun nowMillis(): Long = instant.value

    override fun zone(): ZoneId = zoneId

    /** Data local do instante, a cada avanço; a mesma data não sai duas vezes. */
    override fun dates(): Flow<LocalDate> =
        instant.map { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }.distinctUntilChanged()

    fun advanceTo(millis: Long) {
        instant.value = millis
    }

    fun advanceBy(duration: Duration) = advanceTo(nowMillis() + duration.toMillis())

    /** Vai para [at], hora local no fuso atual. */
    fun setLocal(at: LocalDateTime) = advanceTo(at.atZone(zone()).toInstant().toEpochMilli())

    companion object {
        /** Relógio parado em [at] (hora local em [zone]). */
        fun at(at: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): FakeAppClock =
            FakeAppClock(at.atZone(zone).toInstant().toEpochMilli(), zone)
    }
}

/**
 * Relógio a partir de funções, para os testes que já controlavam agora e fuso por variáveis (antes
 * passadas como `zone = { }` e `clock = { }`). [AppClock.dates] é o laço de sempre, lendo as duas.
 */
fun clockOf(zone: () -> ZoneId = { ZoneId.systemDefault() }, now: () -> Long): AppClock = object : AppClock {
    override fun nowMillis(): Long = now()

    override fun zone(): ZoneId = zone()
}
