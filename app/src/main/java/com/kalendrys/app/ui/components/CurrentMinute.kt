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
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}
