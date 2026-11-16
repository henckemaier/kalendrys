package com.kalendrys.app.ui.shopping

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Dica única dos gestos da lista (ADR-0002, 12.4): aparece com o texto e some ao dispensar. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingGestureHintTest {

    @get:Rule
    val compose = createComposeRule()

    private val message =
        "Deslize um item para a direita para marcar ou desmarcar, e para a esquerda para remover. Toque nele para editar."

    @Test
    fun `aparece com o texto e some ao tocar em Entendi`() {
        var seen by mutableStateOf(false)
        var dismissals = 0
        compose.setContent {
            KalendrysTheme {
                // Como a tela fará: mostra enquanto não foi vista; o dispensar grava "vista".
                if (!seen) {
                    ShoppingGestureHint(onDismiss = {
                        dismissals++
                        seen = true
                    })
                }
            }
        }

        compose.onNodeWithText("Dica").assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()

        compose.onNodeWithText("Entendi").performClick()

        compose.runOnIdle { assertEquals(1, dismissals) }
        assertEquals(0, compose.onAllNodesWithText(message).fetchSemanticsNodes().size)
    }
}
