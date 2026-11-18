package com.kalendrys.app.ui.today

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Timeline com fonte grande (ADR-0002, 14.5; v1.8.0, E4): a altura do bloco sai do CONTEÚDO, não
 * de uma conta pela duração. Blocos de 15, 30 e 60 min com títulos de 60 caracteres, em 360dp, com
 * fonte 2.0 (e 1.0, sem regressão): título e faixa de horário inteiros (sem reticências nem texto
 * fora da caixa), um acima do outro dentro do bloco, e o bloco alto o bastante para os dois horários
 * da coluna e para o círculo mínimo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
// Medida de texto de verdade: no modo padrão do Robolectric o texto não tem largura nem altura reais.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayTimelineMeasureTest {

    @get:Rule
    val compose = createComposeRule()

    /** Altura de uma linha de horário ("00:00", labelMedium) na fonte em vigor, em px. */
    private var timeLabelHeightPx = 0

    private fun title(minutes: Int) = "Rotina de $minutes min com um título bem comprido para testar a quebra".take(60)

    private fun show(fontScale: Float, minutes: Int) {
        val routine = Routine(id = minutes.toLong(), title = title(minutes), startMinute = 7 * 60, durationMinutes = minutes)
        val state = TodayViewModel.UiState(
            isLoaded = true,
            today = LocalDate.now(),
            selectedDate = LocalDate.now(),
            timeline = listOf(
                DayTimeline.Block(routine, done = false, startMinute = routine.startMinute, linkAbove = DayTimeline.Link.SOLID, linkBelow = DayTimeline.Link.SOLID, occurrenceDate = LocalDate.now()),
            ),
        )
        val actions = TrailActions({}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    val measurer = rememberTextMeasurer()
                    timeLabelHeightPx = measurer.measure("00:00", MaterialTheme.typography.labelMedium).size.height
                    LazyColumn(Modifier.width(360.dp)) { todayTrail(state, nowMinute = 0, actions = actions) }
                }
            }
        }
        compose.waitForIdle()
    }

    private val hasState = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)

    private fun unmerged(text: String, substring: Boolean = false): SemanticsNode =
        compose.onNode(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNode()

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun assertWhole(label: String, node: SemanticsNode) {
        val layout = node.layout()
        assertFalse("$label: texto fora da caixa", layout.hasVisualOverflow)
        assertTrue("$label: linha com reticências", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
    }

    private fun Rect.contains(other: Rect) =
        other.top >= top - 0.5f && other.bottom <= bottom + 0.5f

    private fun assertBlockFits(fontScale: Float, minutes: Int) {
        show(fontScale, minutes)
        val label = "fonte $fontScale, $minutes min"
        val row = compose.onNode(hasText(title(minutes), substring = true) and hasState).fetchSemanticsNode()
        val titleNode = unmerged(title(minutes))
        val rangeNode = unmerged("07:00–", substring = true)

        assertEquals("$label: título de 60 caracteres", 60, title(minutes).length)
        assertWhole("$label, título", titleNode)
        assertWhole("$label, horário", rangeNode)

        val rowBounds = row.boundsInRoot
        val titleBounds = titleNode.boundsInRoot
        val rangeBounds = rangeNode.boundsInRoot
        assertTrue("$label: horário dentro do bloco", rowBounds.contains(rangeBounds))
        assertTrue("$label: título dentro do bloco", rowBounds.contains(titleBounds))
        assertTrue("$label: horário acima do título, sem sobrepor", rangeBounds.bottom <= titleBounds.top + 0.5f)

        // Coluna de horas: início e fim, cada um numa linha, com o respiro de cima e de baixo.
        val density = compose.density.density
        val timeColumn = 2 * timeLabelHeightPx + 2 * (RowPadding.value + 2) * density
        assertTrue("$label: bloco ${rowBounds.height} < coluna de horas $timeColumn", rowBounds.height >= timeColumn - 0.5f)
        // Círculo mínimo da pílula com o respiro.
        val pill = (48 + 2 * RowPadding.value) * density
        assertTrue("$label: bloco ${rowBounds.height} < pílula mínima $pill", rowBounds.height >= pill - 0.5f)
    }

    @Test
    fun `fonte 2_0 - bloco de 15 min com titulo longo inteiro`() = assertBlockFits(2f, 15)

    @Test
    fun `fonte 2_0 - bloco de 30 min com titulo longo inteiro`() = assertBlockFits(2f, 30)

    @Test
    fun `fonte 2_0 - bloco de 60 min com titulo longo inteiro`() = assertBlockFits(2f, 60)

    @Test
    fun `fonte 1_0 - bloco de 30 min inteiro`() = assertBlockFits(1f, 30)

    @Test
    fun `fonte 1_0 - bloco de 15 min inteiro`() = assertBlockFits(1f, 15)

    @Test
    fun `fonte 1_0 - bloco de 60 min inteiro`() = assertBlockFits(1f, 60)
}
