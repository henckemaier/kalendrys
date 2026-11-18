package com.kalendrys.app.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Painel de nova tarefa em ALTURA COMPACTA (ADR-0002, 14.7; v1.8.0, E6; fecha L29 e O-1): título,
 * Cancelar e Salvar numa linha fixa no topo, nessa ordem (Salvar por último), e os campos rolam
 * abaixo. Com a largura apertada e fonte 2.0 quem cede é o título (reticências); os botões nunca
 * cortam. Focar o último campo (rolando) não leva a linha fixa embora.
 */
@RunWith(AndroidJUnit4::class)
// Medida de texto de verdade: reticências e largura dos botões dependem da fonte.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewTaskSheetCompactTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A escala de fonte vai pela CONFIGURAÇÃO (como no aparelho), não por um `LocalDensity`: o painel
     * é janela própria e o `ComposeView` dela fornece um `LocalDensity` novo, do próprio contexto, que
     * passaria por cima do fornecido aqui (a fonte 2.0 não chegaria ao painel).
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
                    lists = emptyList(),
                )
            }
        }
        // Painel aberto e parado (fim da animação de entrada) antes de medir.
        compose.waitForIdle()
    }

    /** A raiz da janela do próprio nó (o painel tem janela própria: há mais de uma raiz). */
    private fun SemanticsNode.windowRoot(): SemanticsNode = generateSequence(this) { it.parent }.last()

    private fun text(label: String): SemanticsNode =
        compose.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()

    private fun button(label: String): SemanticsNode = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** Texto inteiro: sem reticências, nada cortado na altura e a linha dentro da caixa. */
    private fun assertWholeText(label: String) {
        val layout = text(label).layout()
        assertTrue("$label: reticências", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        assertFalse("$label: cortado na altura", layout.didOverflowHeight)
        (0 until layout.lineCount).forEach { line ->
            assertTrue("$label: linha fora da caixa", layout.getLineRight(line) <= layout.size.width + 0.5f)
            assertTrue("$label: linha começa antes da caixa", layout.getLineLeft(line) >= -0.5f)
        }
    }

    /** Botão visível por inteiro, dentro da tela, sem rolar. */
    private fun assertWholeButton(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
        val node = button(label)
        val visible = node.boundsInRoot
        assertEquals("$label: largura visível", node.size.width.toFloat(), visible.width, 0.5f)
        assertEquals("$label: altura visível", node.size.height.toFloat(), visible.height, 0.5f)
        val root = node.windowRoot().boundsInRoot
        assertTrue("$label: passa da borda direita", visible.right <= root.right + 0.5f)
        assertWholeText(label)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `paisagem de celular com fonte 2_0 - Cancelar e Salvar inteiros no topo, nessa ordem, sem rolar`() {
        render(fontScale = 2f)

        assertWholeButton("Cancelar")
        assertWholeButton("Salvar")
        val cancel = button("Cancelar").boundsInRoot
        val save = button("Salvar").boundsInRoot
        assertTrue("Salvar por último", save.left >= cancel.right - 0.5f)
        // Na linha do título, no topo do painel (acima do primeiro campo).
        val title = text("Nova tarefa").boundsInRoot
        val firstField = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("linha acima dos campos", save.bottom <= firstField.top + 0.5f)
        assertTrue("título na mesma linha", title.bottom > save.top && title.top < save.bottom)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w420dp-h360dp-land")
    fun `largura apertada com fonte 2_0 - o titulo cede com reticencias e os botoes nao cortam`() {
        render(fontScale = 2f)

        val title = text("Nova tarefa").layout()
        assertTrue("o título cede (reticências)", title.isLineEllipsized(0))
        assertWholeButton("Cancelar")
        assertWholeButton("Salvar")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `com o ultimo campo focado a linha fixa continua visivel`() {
        render(fontScale = 2f)
        val before = button("Salvar").boundsInRoot.top

        val location = compose.onNode(hasSetTextAction() and hasText("Local (opcional)"))
        location.performScrollTo().performClick()
        location.assertIsFocused()

        assertWholeButton("Salvar")
        assertWholeButton("Cancelar")
        assertEquals("a linha não rolou", before, button("Salvar").boundsInRoot.top, 0.5f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `paisagem de celular com fonte 1_0 - mesma linha fixa`() {
        render(fontScale = 1f)

        assertWholeButton("Cancelar")
        assertWholeButton("Salvar")
        assertFalse("sem aperto o título fica inteiro", text("Nova tarefa").layout().isLineEllipsized(0))
    }
}
