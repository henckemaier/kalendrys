package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Painel de nova tarefa com POUCA altura (v1.8.0, O-6): só os campos rolam; Cancelar e Salvar ficam
 * FORA da rolagem nas duas alturas, então o Salvar continua inteiro na área do painel mesmo quando os
 * campos não cabem (o caso do teclado aberto em retrato). Em altura compacta o painel não desenha a
 * alça.
 *
 * O Robolectric não abre teclado: a falta de altura vem da janela (e da fonte 2.0). Que o painel
 * desconta o teclado UMA vez, e que o campo em foco volta à vista quando o teclado sobe, fica para a
 * conferência no emulador.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewTaskSheetLimitedHeightTest {

    @get:Rule
    val compose = createComposeRule()

    /** A fonte vai pela configuração: o painel é janela própria (ver NewTaskSheetCompactTest). */
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
        compose.waitForIdle()
    }

    private fun SemanticsNode.windowRoot(): SemanticsNode = generateSequence(this) { it.parent }.last()

    private fun button(label: String): SemanticsNode =
        compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()

    /** O nó que rola os campos (o único com faixa de rolagem vertical na janela do painel). */
    private fun SemanticsNode.scrollingAncestor(): SemanticsNode? =
        generateSequence(parent) { it.parent }
            .firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }

    /** Inteiro, dentro da janela do painel, sem rolar, e FORA da coluna que rola. */
    private fun assertFixedAndWhole(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
        val node = button(label)
        val visible = node.boundsInRoot
        assertEquals("$label: altura visível", node.size.height.toFloat(), visible.height, 0.5f)
        assertEquals("$label: largura visível", node.size.width.toFloat(), visible.width, 0.5f)
        val root = node.windowRoot().boundsInRoot
        assertTrue("$label: passa do fim do painel", visible.bottom <= root.bottom + 0.5f)
        assertNull("$label: está dentro da rolagem", node.scrollingAncestor())
    }

    private fun dragHandleDescription(): String =
        ApplicationProvider.getApplicationContext<Context>()
            .getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description)

    private fun dragHandleCount(): Int =
        compose.onAllNodes(hasContentDescription(dragHandleDescription()), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h520dp")
    fun `altura normal limitada com fonte 2_0 - os campos rolam e Salvar fica no fim, fora da rolagem`() {
        render(fontScale = 2f)

        // Os campos não cabem: a coluna deles rola (é o caso do teclado aberto em retrato).
        val firstField = compose.onNode(hasSetTextAction() and hasText("Título")).fetchSemanticsNode()
        assertNotNull("os campos rolam", firstField.scrollingAncestor())

        assertFixedAndWhole("Salvar")
        assertFixedAndWhole("Cancelar")
        // No FIM do painel: abaixo do começo dos campos.
        assertTrue("Salvar abaixo dos campos", button("Salvar").boundsInRoot.top >= firstField.boundsInRoot.top)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h520dp")
    fun `altura normal limitada - focar o ultimo campo rola os campos e nao leva o Salvar`() {
        render(fontScale = 2f)
        val before = button("Salvar").boundsInRoot.top

        val location = compose.onNode(hasSetTextAction() and hasText("Local (opcional)"))
        location.performScrollTo().performClick()
        location.assertIsFocused()

        assertFixedAndWhole("Salvar")
        assertEquals("o Salvar não se moveu", before, button("Salvar").boundsInRoot.top, 0.5f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
    fun `altura compacta - Salvar fora da rolagem e sem a alca`() {
        render(fontScale = 2f)

        assertFixedAndWhole("Salvar")
        assertFixedAndWhole("Cancelar")
        assertEquals("sem a alça em altura compacta", 0, dragHandleCount())
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `altura normal - a alca continua`() {
        render(fontScale = 1f)

        // Controle do teste acima: o mesmo localizador acha a alça quando ela existe.
        assertEquals(1, dragHandleCount())
        assertFixedAndWhole("Salvar")
    }
}
