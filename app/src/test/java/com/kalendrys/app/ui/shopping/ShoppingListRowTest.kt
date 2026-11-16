package com.kalendrys.app.ui.shopping

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cartão da aba Listas (piloto da E2, parecer do Esquadro A1): um nó focável só por cartão além do
 * menu, com a ação de clique e o rótulo no nó que tem o nome da lista e o progresso lido junto.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingListRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val summary = ShoppingListSummary(
        list = ShoppingList(id = 10, name = "Mercado"),
        progress = ShoppingProgress(picked = 3, total = 8),
        totalCents = 0,
        owners = listOf(ShoppingListOwner(ShoppingListOwner.Kind.TASK, id = 2, title = "Churrasco")),
    )

    @Test
    fun `um no clicavel para o cartao e outro so para o menu`() {
        var opened = 0
        compose.setContent {
            KalendrysTheme {
                ShoppingListRow(summary = summary, index = 0, count = 1, onOpen = { opened++ }, onRename = {}, onDelete = {})
            }
        }

        // O nó do cartão: nome, apoio e progresso mesclados, com o clique e o rótulo do clique.
        val card = compose.onNodeWithText("Mercado")
        val node = card.fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.OnClick))
        assertEquals("abrir a lista", node.config[SemanticsActions.OnClick].label)
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("progresso no mesmo nó: $descriptions", descriptions.any { it.contains("3 de 8") })

        // Clicáveis na árvore mesclada: o cartão e o botão do menu, nada mais.
        assertEquals(2, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)

        card.performClick()
        assertEquals(1, opened)
    }
}
