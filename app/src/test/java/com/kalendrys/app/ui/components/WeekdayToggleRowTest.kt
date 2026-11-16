package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Seletor de dias (v1.7.1, E2): cada dia é uma célula de 48dp de altura e 1/7 da linha, sem vão
 * entre alvos; nome completo e estado lidos pelo leitor de tela; fonte 2.0 quebra sem cortar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
// Texto medido com fonte de verdade (no modo padrão do Robolectric a largura do texto é 0).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeekdayToggleRowTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected by mutableStateOf(setOf(DayOfWeek.MONDAY))
    private lateinit var pills: List<Int>
    private lateinit var normalPills: List<Int>

    private fun render(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    pills = weekdayPillWidthsPx()
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                        normalPills = weekdayPillWidthsPx()
                    }
                    // Mesma margem lateral das telas (16dp de cada lado).
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        WeekdayToggleRow(
                            isSelected = { it in selected },
                            onToggle = { day -> selected = if (day in selected) selected - day else selected + day },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `fonte normal em 360dp tem sete celulas de 48dp numa linha e sem vao`() {
        render(fontScale = 1f)

        val lines = compose.assertWeekdayCellsFit(pills, compose.density.density)

        assertEquals(1, lines)
        // Largura igual: cada uma é 1/7 da linha (~46dp com 16dp de margem em 360dp).
        val widths = compose.weekdayCells().map { it.size.width }
        assertTrue("larguras $widths", widths.max() - widths.min() <= 1)
    }

    @Test
    fun `nome completo e estado lidos e o toque na celula alterna o dia`() {
        render(fontScale = 1f)

        val states = compose.weekdayCells().map { it.config[SemanticsProperties.ToggleableState] }
        val expected = WEEKDAY_FULL_NAMES.map { if (it == "segunda-feira") ToggleableState.On else ToggleableState.Off }
        assertEquals(expected, states)

        compose.onNode(hasContentDescription("quarta-feira")).performClick()

        compose.runOnIdle { assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), selected) }
        assertEquals(
            ToggleableState.On,
            compose.onNode(hasContentDescription("quarta-feira")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState],
        )
    }

    @Test
    fun `fonte 2_0 em 360dp quebra em mais linhas sem cortar e sem vao`() {
        render(fontScale = 2f)

        // A escala vale: a pílula de cada dia fica mais larga que na fonte normal.
        pills.zip(normalPills).forEach { (big, small) -> assertTrue("pílula $big <= $small", big > small) }
        // Nenhuma célula corta a pílula nem passa da tela; as sete não cabem numa linha só.
        val lines = compose.assertWeekdayCellsFit(pills, compose.density.density)
        assertTrue("linhas: $lines", lines > 1)
        // As linhas seguintes mantêm a largura de célula da primeira.
        val widths = compose.weekdayCells().map { it.size.width }
        assertTrue("larguras $widths", widths.max() - widths.min() <= 1)
    }

    @Test
    fun `quando nao cabem sete as linhas sao repartidas por igual`() {
        // A conta: o mínimo de linhas, com no máximo um dia de diferença entre elas.
        assertEquals(listOf(7), weekdayRowSizes(7))
        listOf(6, 5, 4).forEach { assertEquals("cabem $it", listOf(4, 3), weekdayRowSizes(it)) }
        assertEquals(listOf(3, 2, 2), weekdayRowSizes(3))
        assertEquals(listOf(2, 2, 2, 1), weekdayRowSizes(2))
        assertEquals(List(7) { 1 }, weekdayRowSizes(1))

        // Na tela, em 360dp e fonte 2.0: nenhuma linha com um dia sozinho embaixo de uma cheia.
        render(fontScale = 2f)
        val perLine = compose.weekdayCells().groupBy { it.positionInRoot.y }.values.map { it.size }
        assertTrue("dias por linha: $perLine", perLine.size > 1 && perLine.max() - perLine.min() <= 1)
        compose.assertWeekdayCellsFit(pills, compose.density.density)
    }
}
