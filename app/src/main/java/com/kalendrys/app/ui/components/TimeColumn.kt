package com.kalendrys.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * Largura de uma coluna de horário ("03:35") ao lado de um título: [minWidth] (o layout de sempre na
 * fonte 1.0) ou, se a fonte do sistema não deixar "00:00" caber nela com [endGap] de folga até o
 * título, a largura medida do texto + [endGap]. Medida, não fixa, para o horário nunca quebrar
 * ("03:" / "35") nem encostar no título com fonte grande. Use com o texto em `maxLines = 1`.
 *
 * Usada pela timeline de Rotinas e pela lista do dia do Calendário.
 */
@Composable
fun rememberTimeColumnWidth(minWidth: Dp, style: TextStyle, endGap: Dp): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    return remember(measurer, style, density, minWidth, endGap) {
        val measured = measurer.measure(WIDEST_TIME, style, maxLines = 1, softWrap = false).size.width
        maxOf(minWidth, with(density) { measured.toDp() } + endGap)
    }
}

/** Horário de referência para medir: dígitos têm a mesma largura, então "00:00" vale para todos. */
private const val WIDEST_TIME = "00:00"
