package com.kalendrys.app.data.repository

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * O relógio do app (v1.10.0, ADR-0002 16.3): a ÚNICA peça que lê a hora e o fuso do aparelho.
 * Criado no [com.kalendrys.app.KalendrysApp] e passado por parâmetro a repositórios, ViewModels,
 * catch-ups, receivers e agendadores; as telas o leem por `LocalAppClock`, só para valor que é da
 * própria tela. O domínio continua puro: recebe data, instante e fuso, nunca o relógio. Fora daqui
 * (e da Migration já publicada), nada de `LocalDate.now()`, `System.currentTimeMillis()` nem
 * `ZoneId.systemDefault()` no app: o `SourceClockReadsTest` confere.
 *
 * Só DOIS primitivos, [nowMillis] e [zone], lidos a cada chamada (o fuso nunca é guardado). Tudo o
 * mais sai deles ([now], [today], [nowLocal], [timeOfDay] e o fluxo [dates]): agora e hoje não têm
 * como discordar, nem num relógio de teste.
 */
interface AppClock {

    /** Agora, em milissegundos desde a época. */
    fun nowMillis(): Long

    /** O fuso de agora. */
    fun zone(): ZoneId

    /**
     * A data de hoje, emitida ao começar e de novo logo após cada meia-noite (a mesma data não sai
     * duas vezes). Fluxo frio: cada coleta lê o relógio ao começar, então quem recomeça a coleta
     * (WhileSubscribed, o refresh do [TodayRepository]) mede de novo a próxima virada. Uma troca de
     * hora ou de fuso com a coleta em andamento só aparece na próxima meia-noite ou ao recomeçar,
     * como até a v1.9.x.
     */
    fun dates(): Flow<LocalDate> = flow {
        while (true) {
            val now = now()
            emit(now.toLocalDate())
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(Duration.between(now, nextMidnight).toMillis() + MIDNIGHT_MARGIN_MS)
        }
    }.distinctUntilChanged()
}

private const val MIDNIGHT_MARGIN_MS = 1_000L

/** Agora no fuso de agora. */
fun AppClock.now(): ZonedDateTime = Instant.ofEpochMilli(nowMillis()).atZone(zone())

fun AppClock.nowLocal(): LocalDateTime = now().toLocalDateTime()

fun AppClock.today(): LocalDate = now().toLocalDate()

fun AppClock.timeOfDay(): LocalTime = now().toLocalTime()

/** O relógio de verdade: o do sistema. Até a v1.9.x a data vinha do `currentDate()` solto. */
object SystemAppClock : AppClock {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
