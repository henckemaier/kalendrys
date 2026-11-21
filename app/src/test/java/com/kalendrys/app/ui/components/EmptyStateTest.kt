package com.kalendrys.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `acao secundaria aparece abaixo da principal e as duas sao acessiveis`() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            KalendrysTheme {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Nada no seu dia ainda",
                    message = "Comece por uma tarefa.",
                    actionLabel = "Nova tarefa",
                    onAction = { clicks += "principal" },
                    secondaryActionLabel = "Nova rotina",
                    onSecondaryAction = { clicks += "secundária" },
                )
            }
        }

        compose.onAllNodes(clickable).assertCountEquals(2)
        val primary = compose.onNode(hasText("Nova tarefa") and clickable).fetchSemanticsNode()
        val secondary = compose.onNode(hasText("Nova rotina") and clickable).fetchSemanticsNode()
        assertEquals(Role.Button, primary.config[SemanticsProperties.Role])
        assertEquals(Role.Button, secondary.config[SemanticsProperties.Role])
        assertTrue("secundária abaixo", secondary.boundsInRoot.top >= primary.boundsInRoot.bottom)

        compose.onNodeWithText("Nova rotina").performClick()
        compose.onNodeWithText("Nova tarefa").performClick()
        assertEquals(listOf("secundária", "principal"), clicks)
    }

    @Test
    fun `secundaria so com rotulo e acao juntos`() {
        compose.setContent {
            KalendrysTheme {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Nenhuma lista ainda",
                    message = "Crie uma lista.",
                    secondaryActionLabel = "Nova rotina",
                )
            }
        }

        compose.onAllNodes(clickable).assertCountEquals(0)
    }
}
