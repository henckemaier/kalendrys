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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Timeline com fonte grande (ADR-0002, 14.5; v1.8.0, E4 e O-7): a altura do bloco sai do CONTEÚDO,
 * não de uma conta pela duração. Blocos de 15, 30 e 60 min com títulos de 60 caracteres, em 360dp,
 * com fonte 2.0 (e 1.0, sem regressão): título inteiro (sem reticências nem texto fora da caixa),
 * horário numa linha só acima do título, dentro do bloco, e o bloco alto o bastante para os dois
 * horários da coluna e para o círculo mínimo.
 *
 * O-7: o horário NUNCA quebra (quebrava no meio do número): a faixa inteira numa linha se couber,
 * senão só a duração ("2h"); bloco sem duração mostra só a hora. O leitor de tela lê sempre a frase
 * inteira, como texto do nó. A fonte aqui vai por `LocalDensity`: a trilha não é janela própria.
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

    /** Largura natural, numa linha, da faixa inteira e da forma curta do bloco mostrado, em px. */
    private var fullRangeWidthPx = 0
    private var shortRangeWidthPx = 0

    private val startMinute = 7 * 60

    private fun title(minutes: Int) = "Rotina de $minutes min com um título bem comprido para testar a quebra".take(60)

    /** A frase que o leitor de tela lê (a mesma de routineTimeRange): "07:00–08:00 (1h)" ou "07:00". */
    private fun fullRange(minutes: Int): String =
        if (minutes == 0) {
            minuteOfDayLabel(startMinute)
        } else {
            "${minuteOfDayLabel(startMinute)}–${minuteOfDayLabel(startMinute + minutes)} (${DurationFormat.format(minutes)})"
        }

    /** O que aparece quando a faixa não cabe: só a duração; sem duração, a hora. */
    private fun shortRange(minutes: Int): String =
        if (minutes == 0) minuteOfDayLabel(startMinute) else DurationFormat.format(minutes)

    private fun show(
        fontScale: Float,
        minutes: Int,
        columnWidth: Dp = 360.dp,
        remind: Boolean = false,
        withList: Boolean = false,
    ) {
        val routine = Routine(
            id = minutes + 1L,
            title = title(minutes),
            startMinute = startMinute,
            durationMinutes = minutes,
            remind = remind,
            shoppingListId = if (withList) LIST_ID else null,
        )
        val state = TodayViewModel.UiState(
            isLoaded = true,
            today = LocalDate.now(),
            selectedDate = LocalDate.now(),
            timeline = listOf(
                DayTimeline.Block(routine, done = false, startMinute = routine.startMinute, linkAbove = DayTimeline.Link.SOLID, linkBelow = DayTimeline.Link.SOLID, occurrenceDate = LocalDate.now()),
            ),
            // O "2/3" da lista anexada, ao lado do horário (M-1).
            listProgress = if (withList) mapOf(LIST_ID to ShoppingProgress(picked = 2, total = 3)) else emptyMap(),
        )
        val actions = TrailActions({}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelMedium
                    timeLabelHeightPx = measurer.measure("00:00", style).size.height
                    fullRangeWidthPx = measurer.measure(fullRange(minutes), style, maxLines = 1, softWrap = false).size.width
                    shortRangeWidthPx = measurer.measure(shortRange(minutes), style, maxLines = 1, softWrap = false).size.width
                    LazyColumn(Modifier.width(columnWidth)) { todayTrail(state, nowMinute = 0, actions = actions) }
                }
            }
        }
        compose.waitForIdle()
    }

    private val hasState = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)

    private fun unmerged(text: String, substring: Boolean = false): SemanticsNode =
        compose.onNode(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNode()

    /**
     * O nó do horário desenhado (O-7, N-2), pela marca de teste: sem semântica própria desde a N-8 (a
     * frase vem da linha que o contém); serve só para medir o que aparece.
     */
    private fun rangeNode(): SemanticsNode =
        compose.onNode(hasTestTag(TIME_RANGE_TAG), useUnmergedTree = true).fetchSemanticsNode()

    /**
     * N-8: o bloco, na árvore MESCLADA (o que o leitor de tela foca), expõe a frase inteira do horário
     * em qualquer degrau, uma vez só. Sem duração a frase é a hora, que a coluna de horas também diz.
     */
    private fun assertPhraseOnce(label: String, minutes: Int) {
        val row = compose.onNode(hasText(title(minutes), substring = true) and hasState).fetchSemanticsNode()
        val texts = row.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val count = texts.count { it == fullRange(minutes) }
        if (minutes > 0) {
            assertEquals("$label: frase do horário no bloco ($texts)", 1, count)
        } else {
            assertTrue("$label: hora no bloco ($texts)", count >= 1)
        }
    }

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * "Sem corte" medido no que aparece: nenhuma linha com reticências, nada cortado na altura e cada
     * linha terminando dentro da caixa. NÃO usa `hasVisualOverflow`: um texto de uma linha só, com
     * `weight(1f, fill = false)`, tem a caixa encolhida ao texto enquanto o parágrafo é diagramado na
     * largura da restrição, e o `didOverflowWidth` acusa sem haver corte (diagnóstico da E4).
     */
    private fun assertWhole(label: String, node: SemanticsNode) {
        val layout = node.layout()
        assertTrue("$label: linha com reticências", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        assertFalse("$label: cortado na altura", layout.didOverflowHeight)
        val boxWidth = layout.size.width
        (0 until layout.lineCount).forEach { line ->
            // Início e fim de cada linha dentro da caixa: não depende do alinhamento à esquerda.
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            assertTrue("$label: linha $line começa em $left, antes da caixa", left >= -0.5f)
            assertTrue("$label: linha $line termina em $right, fora da caixa de $boxWidth", right <= boxWidth + 0.5f)
        }
    }

    /**
     * O-7: o bloco expõe a frase inteira ao leitor de tela (N-8, pela linha do horário); na tela o horário
     * ocupa UMA linha, com a largura natural da faixa inteira (coube) ou da forma curta (não coube),
     * nunca quebrado nem cortado. Devolve o nó.
     */
    private fun assertRangeOnOneLine(label: String, minutes: Int): SemanticsNode {
        val node = rangeNode()
        assertPhraseOnce(label, minutes)

        val size = node.size
        assertTrue("$label: horário com ${size.height}px, mais de uma linha ($timeLabelHeightPx)", size.height <= timeLabelHeightPx + 1)
        assertTrue("$label: horário sem altura", size.height > 0)
        val showsFull = abs(size.width - fullRangeWidthPx) <= 1
        val showsShort = abs(size.width - shortRangeWidthPx) <= 1
        assertTrue(
            "$label: largura ${size.width} não é a da faixa ($fullRangeWidthPx) nem a da duração ($shortRangeWidthPx): cortada",
            showsFull || showsShort,
        )
        // Só a duração quando a faixa inteira não caberia.
        if (!showsFull) assertTrue("$label: trocou pela duração sem precisar", fullRangeWidthPx > size.width)
        return node
    }

    private fun Rect.contains(other: Rect) =
        other.top >= top - 0.5f && other.bottom <= bottom + 0.5f && other.left >= left - 0.5f && other.right <= right + 0.5f

    private fun assertBlockFits(fontScale: Float, minutes: Int) {
        show(fontScale, minutes)
        val label = "fonte $fontScale, $minutes min"
        val row = compose.onNode(hasText(title(minutes), substring = true) and hasState).fetchSemanticsNode()
        val titleNode = unmerged(title(minutes))

        assertEquals("$label: título de 60 caracteres", 60, title(minutes).length)
        assertWhole("$label, título", titleNode)
        val rangeNode = assertRangeOnOneLine("$label, horário", minutes)

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
        // Pílula mínima pela duração (48..160dp) com o respiro: o bloco nunca fica abaixo dela.
        val pill = (pillMinDp(minutes) + 2 * RowPadding.value) * density
        assertTrue("$label: bloco ${rowBounds.height} < pílula mínima $pill", rowBounds.height >= pill - 0.5f)
    }

    /** A mesma conta do mínimo da pílula na timeline: duração × 1,6 limitada a 48..160dp. */
    private fun pillMinDp(minutes: Int): Float = (minutes * 1.6f).coerceIn(48f, 160f)

    /** Vários blocos de título CURTO na mesma trilha (fonte em vigor); devolve a altura de cada um, em px. */
    private fun shortTitleHeights(fontScale: Float, durations: List<Int>): List<Float> {
        val blocks = durations.mapIndexed { i, minutes ->
            val routine = Routine(id = i + 1L, title = "Bloco $minutes", startMinute = (6 + i * 3) * 60, durationMinutes = minutes)
            DayTimeline.Block(
                routine,
                done = false,
                startMinute = routine.startMinute,
                linkAbove = DayTimeline.Link.NONE,
                linkBelow = DayTimeline.Link.NONE,
                occurrenceDate = LocalDate.now(),
            )
        }
        val state = TodayViewModel.UiState(isLoaded = true, timeline = blocks)
        val actions = TrailActions({}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    LazyColumn(Modifier.width(360.dp)) { todayTrail(state, nowMinute = 0, actions = actions) }
                }
            }
        }
        compose.waitForIdle()
        return durations.map { minutes ->
            compose.onNode(hasText("Bloco $minutes", substring = true) and hasState).fetchSemanticsNode().boundsInRoot.height
        }
    }

    @Test
    fun `fonte 1_0 com titulo curto - bloco mais longo continua mais alto`() {
        val (h15, h60, h120) = shortTitleHeights(1f, listOf(15, 60, 120))

        assertTrue("60 min ($h60) mais alto que 15 min ($h15)", h60 > h15)
        assertTrue("2 h ($h120) mais alto que 60 min ($h60)", h120 > h60)
        // E cada um com pelo menos a pílula mínima da sua duração.
        val density = compose.density.density
        listOf(15 to h15, 60 to h60, 120 to h120).forEach { (minutes, height) ->
            val pill = (pillMinDp(minutes) + 2 * RowPadding.value) * density
            assertTrue("$minutes min: $height < pílula mínima $pill", height >= pill - 0.5f)
        }
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

    // --- O-7: horário numa linha só ------------------------------------------------------------------

    private fun assertRangeOnOneLineAt(width: Dp, minutes: Int) {
        show(fontScale = 2f, minutes = minutes, columnWidth = width)
        assertRangeOnOneLine("fonte 2.0, ${width.value.toInt()}dp, $minutes min", minutes)
    }

    @Test
    fun `horario numa linha so - 320dp, fonte 2_0, bloco de 2h`() = assertRangeOnOneLineAt(320.dp, 120)

    @Test
    fun `horario numa linha so - 320dp, fonte 2_0, bloco de 30 min`() = assertRangeOnOneLineAt(320.dp, 30)

    @Test
    fun `horario numa linha so - 360dp, fonte 2_0, bloco de 2h`() = assertRangeOnOneLineAt(360.dp, 120)

    @Test
    fun `horario numa linha so - 360dp, fonte 2_0, bloco de 30 min`() = assertRangeOnOneLineAt(360.dp, 30)

    /** Visível por inteiro: a parte recortada pelos pais ([SemanticsNode.boundsInRoot]) é o nó todo. */
    private fun assertWholeNode(label: String, node: SemanticsNode) {
        assertEquals("$label: largura visível", node.size.width.toFloat(), node.boundsInRoot.width, 0.5f)
        assertEquals("$label: altura visível", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
    }

    /** Os `Text` desenhados do horário (com layout de texto, ao contrário do nó do `Layout`). */
    private fun drawnText(text: String) =
        compose.onNode(
            hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        )

    @Test
    fun `horario de duracao longa com sino e lista - terceiro degrau, nada desenhado`() {
        // N-2 (antes NB-1, com reticências): 23h 59min em 320dp com fonte 2.0, sino e lista juntos:
        // nem a forma curta cabe, então o horário NÃO aparece (nem cortado); a frase segue no leitor.
        val minutes = 23 * 60 + 59
        show(fontScale = 2f, minutes = minutes, columnWidth = 320.dp, remind = true, withList = true)
        val label = "fonte 2.0, 320dp, 23h59 com sino e lista"

        val range = rangeNode()
        assertPhraseOnce(label, minutes)
        assertEquals("$label: nada desenhado, sem largura", 0, range.size.width)
        assertTrue("$label: altura de uma linha", range.size.height in 1..(timeLabelHeightPx + 1))
        drawnText(fullRange(minutes)).assertIsNotDisplayed()
        drawnText(shortRange(minutes)).assertIsNotDisplayed()

        val bell = compose.onNode(hasContentDescription("Avisa no início"), useUnmergedTree = true).fetchSemanticsNode()
        val tag = compose.onNode(hasContentDescription("2 de 3", substring = true), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            "$label: horário (${range.boundsInRoot.right}) passa por cima do sino (${bell.boundsInRoot.left})",
            range.boundsInRoot.right <= bell.boundsInRoot.left + 0.5f,
        )
        assertTrue("$label: sino depois do horário e antes do 2/3", bell.boundsInRoot.right <= tag.boundsInRoot.left + 0.5f)
        assertWholeNode("$label, sino", bell)
        assertWholeNode("$label, 2/3", tag)
    }

    /** N-2: em qualquer largura o horário é a faixa inteira, só a duração ou nada (largura zero). */
    private fun assertOneOfThreeSteps(width: Dp) {
        val minutes = 23 * 60 + 59
        show(fontScale = 2f, minutes = minutes, columnWidth = width, remind = true, withList = true)
        val label = "fonte 2.0, ${width.value.toInt()}dp"
        val w = rangeNode().size.width
        // N-8: em qualquer degrau, a frase inteira segue no bloco (árvore mesclada), uma vez.
        assertPhraseOnce(label, minutes)
        val ok = abs(w - fullRangeWidthPx) <= 1 || abs(w - shortRangeWidthPx) <= 1 || w == 0
        assertTrue("$label: largura $w não é faixa ($fullRangeWidthPx), duração ($shortRangeWidthPx) nem zero", ok)
        if (w == 0) drawnText(shortRange(minutes)).assertIsNotDisplayed()
    }

    @Test
    fun `tres degraus - 480dp`() = assertOneOfThreeSteps(480.dp)

    @Test
    fun `tres degraus - 360dp`() = assertOneOfThreeSteps(360.dp)

    @Test
    fun `tres degraus - 320dp`() = assertOneOfThreeSteps(320.dp)

    @Test
    fun `horario numa linha so - bloco sem duracao mostra so a hora`() {
        show(fontScale = 2f, minutes = 0, columnWidth = 320.dp)
        val node = assertRangeOnOneLine("fonte 2.0, 320dp, sem duração", minutes = 0)
        // Sem duração, faixa e forma curta são a mesma hora.
        assertTrue(abs(node.size.width - shortRangeWidthPx) <= 1)
    }

    private companion object {
        const val LIST_ID = 7L
    }
}
