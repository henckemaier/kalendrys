package com.kalendrys.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Foco do `QuickAddField` (ADR-0002, 12.7): Enter adiciona, limpa e o campo continua focado. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickAddFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val added = mutableListOf<String>()

    private val field get() = compose.onNode(hasSetTextAction())

    private fun hasEditableText(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    @Before
    fun setUp() {
        compose.setContent {
            KalendrysTheme {
                // O texto é do chamador, como numa tela de verdade.
                var text by remember { mutableStateOf("") }
                QuickAddField(
                    value = text,
                    onValueChange = { text = it },
                    onAdd = { added += it },
                    placeholder = "Adicionar item",
                    addLabel = "Adicionar",
                )
            }
        }
        field.performClick()
    }

    @Test
    fun `Enter adiciona o texto, limpa o campo e mantem o foco para o proximo`() {
        field.performTextInput("Leite")
        field.performImeAction()

        assertEquals(listOf("Leite"), added)
        field.assert(hasEditableText("")).assertIsFocused()

        // Segue digitando sem tocar no campo de novo; espaços nas pontas não entram.
        field.performTextInput("  Pão  ")
        field.performImeAction()

        assertEquals(listOf("Leite", "Pão"), added)
        field.assert(hasEditableText("")).assertIsFocused()
    }

    @Test
    fun `texto em branco nao adiciona e o campo continua focado`() {
        field.performImeAction()
        field.performTextInput("   ")
        field.performImeAction()

        assertEquals(emptyList<String>(), added)
        field.assertIsFocused()
    }

    @Test
    fun `botao de adicionar faz o mesmo que o Enter e devolve o foco ao campo`() {
        field.performTextInput("Café")
        compose.onNodeWithContentDescription("Adicionar").performClick()

        assertEquals(listOf("Café"), added)
        field.assert(hasEditableText("")).assertIsFocused()
    }
}
