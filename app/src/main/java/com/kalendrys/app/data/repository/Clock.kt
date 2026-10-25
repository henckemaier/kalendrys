package com.kalendrys.app.data.repository

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

private const val MIDNIGHT_MARGIN_MS = 1_000L

/**
 * A data de hoje, emitida de novo logo após cada meia-noite. Com o app em segundo plano o
 * fluxo é cancelado (WhileSubscribed) e recomeça com a data certa ao voltar.
 */
fun currentDate(): Flow<LocalDate> = flow {
    while (true) {
        val now = ZonedDateTime.now()
        emit(now.toLocalDate())
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        delay(Duration.between(now, nextMidnight).toMillis() + MIDNIGHT_MARGIN_MS)
    }
}.distinctUntilChanged()
