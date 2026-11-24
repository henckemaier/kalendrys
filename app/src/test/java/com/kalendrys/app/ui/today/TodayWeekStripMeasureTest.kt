package com.kalendrys.app.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Faixa da semana da Hoje (v1.9.0, N-1; ADR-0002, 15.7): o nome do dia fica SEMPRE numa linha. Três
 * letras quando as sete cabem; senão a faixa inteira passa a uma letra, por medida, igual nos sete
 * dias. O leitor de tela ouve o nome completo (descrição da célula).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
// Medida de texto de verdade: no modo padrão do Robolectric o texto não tem largura nem altura reais.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayWeekStripMeasureTest {

    @get:Rule
    val compose = createComposeRule()

    private var lineHeightPx = 0
    private var widestShortPx = 0
    private var widestNarrowPx = 0

    private val today = LocalDate.now()
    private val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek()))

    private fun show(width: Dp, fontScale: Float) {
        val week = (0L until 7L).map { TodayViewModel.WeekDay(weekStart.plusDays(it), emptyList(), 0, 0) }
        val state = TodayViewModel.UiState(isLoaded = true, today = today, selectedDate = today, week = week)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    val measurer = rememberTextMeasurer()
                    val style = weekdayLabelStyle()
                    fun widest(names: List<String>) =
                        names.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
                    widestShortPx = widest(listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"))
                    widestNarrowPx = widest(listOf("D", "S", "T", "Q"))
                    lineHeightPx = measurer.measure("Dom", style, maxLines = 1).size.height
                    Box(Modifier.width(width)) {
                        TodayWeekStrip(state, onSelect = {}, onPreviousWeek = {}, onNextWeek = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Os sete nomes: numa linha só, dentro da célula e todos no mesmo tamanho (curto ou de uma letra). */
    private fun assertNames(label: String, width: Dp, expectShort: Boolean) {
        val names = compose.onAllNodes(hasTestTag(WEEKDAY_NAME_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("$label: sete nomes", 7, names.size)
        val cellPx = with(compose.density) { (width / 7).toPx() }
        names.forEach { node ->
            assertTrue("$label: nome com ${node.size.height}px, mais de uma linha ($lineHeightPx)", node.size.height <= lineHeightPx + 1)
            assertTrue("$label: nome (${node.size.width}px) passa da célula ($cellPx)", node.size.width <= cellPx + 0.5f)
        }
        val widest = names.maxOf { it.size.width }
        if (expectShort) {
            assertTrue("$label: esperava três letras ($widestShortPx), veio $widest", abs(widest - widestShortPx) <= 1)
        } else {
            assertTrue("$label: esperava uma letra (<= $widestNarrowPx), veio $widest", widest <= widestNarrowPx + 1)
        }
        // O leitor de tela segue com o nome completo, em qualquer tamanho.
        compose.onNode(hasContentDescription("domingo", substring = true, ignoreCase = true)).assertExists()
    }

    private fun shortFits(width: Dp): Boolean = widestShortPx <= with(compose.density) { (width / 7).roundToPx() }

    @Test
    fun `fonte 1_0 em 360dp - tres letras`() {
        show(360.dp, 1f)
        assertTrue("o caso de 360dp com fonte 1.0 cabe com folga", shortFits(360.dp))
        assertNames("fonte 1.0, 360dp", 360.dp, expectShort = true)
    }

    @Test
    fun `fonte 2_0 em 320dp - uma letra para os sete`() {
        show(296.dp, 2f) // 320dp menos as margens da lista da Hoje
        assertTrue("o caso estreito precisa NÃO caber em três letras", !shortFits(296.dp))
        assertNames("fonte 2.0, 320dp", 296.dp, expectShort = false)
    }

    @Test
    fun `fonte 2_0 em 360dp - escolha por medida, numa linha`() {
        show(336.dp, 2f)
        assertNames("fonte 2.0, 360dp", 336.dp, expectShort = shortFits(336.dp))
    }

    @Test
    fun `nada cabe - uma letra, sem quebrar`() {
        show(140.dp, 2f)
        val names = compose.onAllNodes(hasTestTag(WEEKDAY_NAME_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        names.forEach { assertTrue("uma linha", it.size.height <= lineHeightPx + 1) }
        assertTrue(names.maxOf { it.size.width } <= widestNarrowPx + 1)
    }
}
