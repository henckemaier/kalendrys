package com.kalendrys.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/** Nomes completos dos dias, de domingo a sábado (o que o leitor de tela lê em cada célula). */
internal val WEEKDAY_FULL_NAMES =
    listOf("domingo", "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado")

private val WEEKDAY_SHORT_NAMES = listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")

private val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

/** As sete células de dia do [WeekdayToggleRow], de domingo a sábado. */
internal fun SemanticsNodeInteractionsProvider.weekdayCells(): List<SemanticsNode> =
    WEEKDAY_FULL_NAMES.map { onNode(hasContentDescription(it) and checkbox).fetchSemanticsNode() }

/**
 * Largura em px da pílula de cada dia ("Dom" … "Sáb" em labelLarge, mais os 6dp de cada lado), na
 * densidade e na escala de fonte em vigor: a célula precisa ser pelo menos isso para não cortar.
 */
@Composable
internal fun weekdayPillWidthsPx(): List<Int> {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val padding = with(LocalDensity.current) { 12.dp.roundToPx() }
    return WEEKDAY_SHORT_NAMES.map { label ->
        measurer.measure(label, style, maxLines = 1, softWrap = false).size.width + padding
    }
}

/**
 * Confere as células: altura de toque >= 48dp; vizinhos na mesma linha encostam (borda direita de
 * um = borda esquerda do outro); nenhuma passa da borda direita da tela; cada uma cabe a pílula
 * inteira ([pillWidthsPx]). Devolve quantas linhas o seletor ocupou.
 */
internal fun SemanticsNodeInteractionsProvider.assertWeekdayCellsFit(
    pillWidthsPx: List<Int>,
    density: Float,
): Int {
    val cells = weekdayCells()
    val rootWidth = onRoot().fetchSemanticsNode().size.width
    val minHeightPx = 48 * density
    cells.forEachIndexed { i, cell ->
        val name = WEEKDAY_FULL_NAMES[i]
        assertTrue("$name: altura ${cell.size.height} < 48dp", cell.size.height >= minHeightPx - 0.5f)
        assertTrue("$name: largura ${cell.size.width} < pílula ${pillWidthsPx[i]}", cell.size.width >= pillWidthsPx[i])
        val right = cell.positionInRoot.x + cell.size.width
        assertTrue("$name: passa da tela ($right > $rootWidth)", right <= rootWidth + 0.5f)
    }
    cells.zipWithNext().forEach { (a, b) ->
        if (abs(a.positionInRoot.y - b.positionInRoot.y) < 0.5f) {
            assertEquals("vão entre vizinhos", a.positionInRoot.x + a.size.width, b.positionInRoot.x, 0.5f)
        }
    }
    return cells.map { it.positionInRoot.y }.distinct().size
}
