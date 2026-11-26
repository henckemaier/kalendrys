package com.kalendrys.app.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Título do painel de nova tarefa em janela de altura NORMAL (L29, O-2 da Coruja, v1.6.2): o "Nova
 * tarefa" fica fixo fora da área que rola; rolando, o foco no campo Título o escondia sob a alça de
 * arrastar. A janela BAIXA (o corte que recolhe o FAB) mudou na v1.8.0 (E6, 14.7): linha fixa com
 * título, Cancelar e Salvar no topo; ver [NewTaskSheetCompactTest].
 */
@RunWith(AndroidJUnit4::class)
class NewTaskSheetTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A escala de fonte vai pela CONFIGURAÇÃO (como no aparelho): o painel é janela própria e o
     * `ComposeView` dela fornece um `LocalDensity` novo, que passaria por cima de um fornecido aqui
     * (até a E6 os casos de fonte 2.0 rodavam em 1.0 dentro do painel).
     */
    private fun render(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            KalendrysTheme {
                var draft by remember { mutableStateOf(NewTaskDraft.startingOn(LocalDate.now())) }
                NewTaskSheet(
                    draft = draft,
                    onChange = { transform -> draft = transform(draft) },
                    onSave = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun top(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun titleStaysFixedAfterScrollingToTheEnd() {
        compose.onNodeWithText("Nova tarefa").assertIsDisplayed()
        val before = top("Nova tarefa")
        // Rolar os campos até o fim (como o foco no campo fazia) não leva o título junto. O Salvar não
        // rola mais (O-6): fica fixo no fim do painel, então se rola até o último campo.
        compose.onNode(hasSetTextAction() and hasText("Local (opcional)")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Salvar").assertIsDisplayed()
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

}
