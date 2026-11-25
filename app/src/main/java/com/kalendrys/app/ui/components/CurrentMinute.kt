package com.kalendrys.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Relógio que muda a cada minuto cheio (epoch millis): faz lembretes vencidos perderem o destaque,
 * acende o bloco de rotina em andamento e vira o dia à meia-noite sem depender de outra mudança.
 */
@Composable
fun rememberCurrentMinute(): Long {
    val clock = LocalAppClock.current
    val now by produceState(clock.nowMillis(), clock) {
        while (true) {
            delay(60_000 - clock.nowMillis() % 60_000)
            value = clock.nowMillis()
        }
    }
    return now
}
