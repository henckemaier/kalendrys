package com.kalendrys.app.ui.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Título do painel de nova tarefa.
 * - L29 (O-2 da Coruja, v1.6.2): em janela de altura normal o "Nova tarefa" fica fixo fora da área
 *   que rola; rolando, o foco no campo Título o escondia sob a alça de arrastar.
 * - O-1 da matriz v1.7.1: em janela BAIXA (o corte que recolhe o FAB) o título fixo tirava a altura
 *   que o teclado deixa e o Salvar ficava fora de alcance; ali ele volta para a área que rola.
 */
@RunWith(AndroidJUnit4::class)
class NewTaskSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    var draft by remember { mutableStateOf(NewTaskDraft.startingOn(LocalDate.now())) }
                    NewTaskSheet(
                        draft = draft,
                        onChange = { transform -> draft = transform(draft) },
                        onSave = {},
                        onCancel = {},
                        onDismiss = {},
                        lists = emptyList(),
                    )
                }
            }
        }
    }

    private fun top(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun titleStaysFixedAfterScrollingToTheEnd() {
        compose.onNodeWithText("Nova tarefa").assertIsDisplayed()
        val before = top("Nova tarefa")
        // Rolar até o fim (como o foco no campo fazia) não leva o título junto.
        compose.onNodeWithText("Salvar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nova tarefa").assertIsDisplayed()
        assertEquals(before, top("Nova tarefa"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h600dp-land")
    fun `altura normal com fonte 1_0 mantem o titulo fixo`() {
        render(fontScale = 1f)
        titleStaysFixedAfterScrollingToTheEnd()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h600dp-land")
    fun `altura normal com fonte 2_0 mantem o titulo fixo`() {
        render(fontScale = 2f)
        titleStaysFixedAfterScrollingToTheEnd()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
    fun `retrato mantem o titulo fixo`() {
        render(fontScale = 2f)
        titleStaysFixedAfterScrollingToTheEnd()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `janela baixa rola o titulo junto e o Salvar fica alcancavel`() {
        render(fontScale = 1f)
        val before = top("Nova tarefa")
        compose.onNodeWithText("Salvar").performScrollTo().assertIsDisplayed()
        // O título está dentro da área que rola: subiu junto com os campos.
        assertTrue(top("Nova tarefa") < before)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `janela baixa com fonte 2_0 ainda alcanca o Salvar`() {
        render(fontScale = 2f)
        compose.onNodeWithText("Salvar").performScrollTo().assertIsDisplayed()
    }
}
