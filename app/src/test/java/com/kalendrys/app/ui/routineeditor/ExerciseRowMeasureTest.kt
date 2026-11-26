package com.kalendrys.app.ui.routineeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.SavedExercise
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Achado 2 da Coruja (v1.10.0, T2): em 320dp com fonte 2.0 o nome do exercício virava "Lev..." e
 * "3×10 · 100 kg" quebrava em 3 linhas, porque os 4 ícones da linha tomavam a largura. Agora o nome
 * tem até 2 linhas, sem reticências para "Levantamento terra", e as ações estão no menu da linha e
 * nas ações de acessibilidade dela. O mesmo na linha do painel "Dos salvos".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-port")
// Texto medido com fonte de verdade (no modo padrão do Robolectric a largura do texto é 0).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseRowMeasureTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val item = ExerciseItem(
        id = 7, routineId = 1, name = "Levantamento terra", sets = 3, reps = 10, load = "100 kg",
        restSeconds = 90, position = 0, createdAt = 0L,
    )
    private val saved = SavedExercise(id = 9, name = "Levantamento terra", sets = 3, reps = 10, load = "100 kg", createdAt = 0L)

    private fun render() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                KalendrysTheme {
                    // A margem lateral do editor (16dp de cada lado).
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        ExerciseItemRow(
                            item = item,
                            index = 0,
                            count = 2,
                            onMoveUp = { calls += "up" },
                            onMoveDown = { calls += "down" },
                            onEdit = { calls += "edit" },
                            onDelete = { calls += "delete" },
                        )
                        SavedExerciseRow(
                            item = saved,
                            index = 0,
                            count = 1,
                            onApply = { calls += "apply" },
                            onEdit = { calls += "saved-edit" },
                            onDelete = { calls += "saved-delete" },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutsOf(text: String): List<TextLayoutResult> =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.config.contains(SemanticsActions.GetTextLayoutResult) }
            .map { node -> mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action!!(it) }.single() }

    @Test
    fun `320dp fonte 2 - nome em ate 2 linhas e sem reticencias, legenda em ate 2 linhas`() {
        render()

        val names = layoutsOf("Levantamento terra")
        assertEquals("linha do exercício e do salvo", 2, names.size)
        names.forEach { layout ->
            assertTrue("nome em ${layout.lineCount} linhas", layout.lineCount <= NAME_MAX_LINES)
            assertFalse("nome com reticências", layout.isLineEllipsized(layout.lineCount - 1))
            assertFalse("nome cortado", layout.hasVisualOverflow)
        }
        val subtitles = layoutsOf("3×10 · 100 kg · 1m30s") + layoutsOf("3×10 · 100 kg")
        assertEquals(2, subtitles.size)
        subtitles.forEach { layout ->
            assertTrue("legenda em ${layout.lineCount} linhas", layout.lineCount <= 2)
        }
    }

    @Test
    fun `acoes da linha pelo leitor - editar, descer e remover (sem subir no primeiro)`() {
        render()
        val row = compose.onNode(hasText("Levantamento terra") and hasClickAction() and insideExerciseRow()).fetchSemanticsNode()

        assertEquals("Editar Levantamento terra", row.config[SemanticsActions.OnClick].label)
        val labels = row.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Editar exercício", "Mover para baixo", "Remover exercício"), labels)

        compose.runOnIdle { row.config[SemanticsActions.CustomActions].first { it.label == "Mover para baixo" }.action() }
        assertEquals(listOf("down"), calls)
    }

    @Test
    fun `acoes da linha pelo menu de tres pontos`() {
        render()
        compose.onAllNodes(hasTestTag(ROW_MENU_TAG), useUnmergedTree = true)[0].performClick()

        compose.onNodeWithText("Remover exercício").performClick()

        assertEquals(listOf("delete"), calls)
    }

    @Test
    fun `salvo - tocar aplica e o menu edita e exclui`() {
        render()
        val row = compose.onAllNodes(hasText("Levantamento terra") and hasClickAction()).fetchSemanticsNodes()
            .single { it.config[SemanticsActions.OnClick].label == "Adicionar Levantamento terra à rotina" }
        assertEquals(
            listOf("Editar Levantamento terra", "Excluir Levantamento terra dos salvos"),
            row.config[SemanticsActions.CustomActions].map { it.label },
        )
        assertFalse("o menu não vira um segundo nó", row.config.contains(SemanticsProperties.ContentDescription))

        compose.onAllNodes(hasTestTag(ROW_MENU_TAG), useUnmergedTree = true)[1].performClick()
        compose.onNodeWithText("Excluir Levantamento terra dos salvos").performClick()

        assertEquals(listOf("saved-delete"), calls)
    }

    /** A linha do exercício (não a do salvo): dentro da etiqueta [EXERCISE_ROW_TAG]. */
    private fun insideExerciseRow() = hasAnyAncestor(hasTestTag("$EXERCISE_ROW_TAG${item.id}"))
}
