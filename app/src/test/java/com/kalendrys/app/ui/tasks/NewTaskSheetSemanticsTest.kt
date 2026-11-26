package com.kalendrys.app.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Semântica do painel de nova tarefa (ADR-0002, 14.9; v1.8.0, E7), nas duas alturas: altura normal
 * (retrato: título fixo e botões no fim) e compacta (paisagem: título, Cancelar e Salvar na linha
 * fixa do topo, E6).
 * - Cancelar e Salvar são botões, com o texto como rótulo, nessa ordem de leitura;
 * - Salvar começa desabilitado (sem título) e habilita ao digitar o título;
 * - o título do painel é cabeçalho (heading) nas duas alturas e, mesmo com reticências na linha
 *   compacta, tem o texto inteiro no nó;
 * - os campos (Título, Observações, Local) são lidos com o rótulo.
 */
@RunWith(AndroidJUnit4::class)
// Medida de texto de verdade: as reticências do título dependem da fonte.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewTaskSheetSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    /** A fonte vai pela configuração: o painel é janela própria e ignora um LocalDensity de fora. */
    private fun render(fontScale: Float = 1f) {
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
        compose.waitForIdle()
    }

    private val cancel = hasText("Cancelar") and hasClickAction()
    private val save = hasText("Salvar") and hasClickAction()

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    // --- (a) Cancelar e Salvar: papel de botão, rótulo e ordem de leitura -----------------------------

    private fun assertButtonsInReadingOrder() {
        val buttons = compose.onAllNodes(cancel or save).fetchSemanticsNodes()
        assertEquals("ordem de leitura", listOf(listOf("Cancelar"), listOf("Salvar")), buttons.map { it.texts() })
        buttons.forEach { node ->
            assertEquals("${node.texts()}: papel de botão", Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
    fun `altura normal - Cancelar e Salvar sao botoes com o texto como rotulo, nessa ordem`() {
        render()
        assertButtonsInReadingOrder()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `altura compacta - Cancelar e Salvar sao botoes com o texto como rotulo, nessa ordem`() {
        render()
        assertButtonsInReadingOrder()
    }

    // --- (b) Salvar desabilitado sem título, habilitado ao digitar ------------------------------------

    private fun assertSaveFollowsTitle() {
        compose.onNode(save).assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Título")).performTextInput("Comprar pão")
        compose.waitForIdle()
        compose.onNode(save).assertIsEnabled()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
    fun `altura normal - Salvar desabilitado sem titulo e habilitado ao digitar`() {
        render()
        assertSaveFollowsTitle()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `altura compacta - Salvar desabilitado sem titulo e habilitado ao digitar`() {
        render()
        assertSaveFollowsTitle()
    }

    // --- (c) Título do painel com o texto inteiro no nó -----------------------------------------------

    private fun titleNode(): SemanticsNode = compose.onNode(hasText("Nova tarefa"), useUnmergedTree = true).fetchSemanticsNode()

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun assertTitleIsHeading() {
        assertTrue("o título do painel é cabeçalho", titleNode().config.contains(SemanticsProperties.Heading))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
    fun `altura normal - titulo do painel e cabecalho`() {
        render()
        assertTitleIsHeading()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `altura compacta - titulo da linha fixa e cabecalho`() {
        render()
        assertTitleIsHeading()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
    fun `altura normal - titulo do painel lido inteiro`() {
        render()
        assertEquals(listOf("Nova tarefa"), titleNode().texts())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w420dp-h360dp-land")
    fun `altura compacta apertada - com reticencias o titulo continua inteiro no no`() {
        render(fontScale = 2f)
        val title = titleNode()
        // Na tela ele cede (reticências), mas o leitor de tela recebe o texto todo.
        assertTrue("o título cede na linha fixa", title.layout().isLineEllipsized(0))
        assertEquals(listOf("Nova tarefa"), title.texts())
    }

    // --- (d) Campos lidos com o rótulo ---------------------------------------------------------------

    private fun assertFieldsHaveLabels() {
        val labels = listOf("Título", "Observações (opcional)", "Local (opcional)")
        labels.forEach { label ->
            assertEquals("$label: um campo com esse rótulo", 1, compose.onAllNodes(hasSetTextAction() and hasText(label)).fetchSemanticsNodes().size)
        }
        // E nenhum campo sem rótulo.
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals("campos de texto", labels.size, fields.size)
        fields.forEach { field -> assertTrue("campo sem rótulo: ${field.texts()}", field.texts().any { it in labels }) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
    fun `altura normal - campos lidos com o rotulo`() {
        render()
        assertFieldsHaveLabels()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `altura compacta - campos lidos com o rotulo`() {
        render()
        assertFieldsHaveLabels()
    }
}
