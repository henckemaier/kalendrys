package com.kalendrys.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `EmptyState` (ADR-0002, 4.2): a ação principal é opcional; sem ela, nada clicável aparece (usos atuais). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

    @Test
    fun `com acao mostra o botao e repassa o toque`() {
        var clicks = 0
        compose.setContent {
            KalendrysTheme {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Nenhuma lista ainda",
                    message = "Crie uma lista.",
                    actionLabel = "Nova lista",
                    onAction = { clicks++ },
                )
            }
        }

        compose.onNodeWithText("Nova lista").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `sem acao fica como antes, sem nada clicavel`() {
        compose.setContent {
            KalendrysTheme {
                EmptyState(icon = Icons.Outlined.ShoppingCart, title = "Nenhuma lista ainda", message = "Crie uma lista.")
            }
        }

        compose.onNodeWithText("Nenhuma lista ainda").assertExists()
        compose.onAllNodes(clickable).assertCountEquals(0)
    }
}
