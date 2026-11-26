package com.kalendrys.app.ui.shopping

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Cartão-resumo da lista (ADR-0001) renderizado de verdade: textos visíveis e os dois toques. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingListCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `mostra nome e progresso e repassa abrir e excluir`() {
        // v1.10.0 (ADR-0002 16.10.4): a lista é de uma tarefa; sem etiqueta de donos e sem Desvincular.
        val summary = ShoppingListSummary(
            list = ShoppingList(id = 10, name = "Mercado", createdAt = TEST_CREATED_AT),
            progress = ShoppingProgress(picked = 2, total = 3),
            totalCents = 0,
            owners = listOf(ShoppingListOwner(ShoppingListOwner.Kind.TASK, id = 2, title = "Churrasco")),
        )
        var opened = 0
        var deleted = 0
        compose.setContent {
            KalendrysTheme {
                ShoppingListCard(summary = summary, onOpen = { opened++ }, onDelete = { deleted++ })
            }
        }

        compose.onNodeWithText("Mercado").assertIsDisplayed()
        compose.onNodeWithText("2 de 3 marcados").assertIsDisplayed()
        compose.onAllNodesWithText("Também em: Churrasco").assertCountEquals(0)

        compose.onNodeWithText("Mercado").performClick()
        compose.onNodeWithContentDescription("Excluir lista").performClick()

        assertEquals(1, opened)
        assertEquals(1, deleted)
    }
}
