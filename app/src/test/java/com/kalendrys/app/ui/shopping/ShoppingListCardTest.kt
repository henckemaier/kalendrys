package com.kalendrys.app.ui.shopping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.ShoppingProgress
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
    fun `mostra nome, progresso e outros donos e repassa os toques`() {
        val rotina = ShoppingListOwner(ShoppingListOwner.Kind.ROUTINE, id = 1, title = "Feira de sábado")
        val tarefa = ShoppingListOwner(ShoppingListOwner.Kind.TASK, id = 2, title = "Churrasco")
        val summary = ShoppingListSummary(
            list = ShoppingList(id = 10, name = "Mercado"),
            progress = ShoppingProgress(picked = 2, total = 3),
            totalCents = 0,
            owners = listOf(rotina, tarefa),
        )
        var opened = 0
        var detached = 0
        compose.setContent {
            KalendrysTheme {
                ShoppingListCard(
                    summary = summary,
                    onOpen = { opened++ },
                    onDetach = { detached++ },
                    isCurrentOwner = { it == rotina },
                )
            }
        }

        compose.onNodeWithText("Mercado").assertIsDisplayed()
        compose.onNodeWithText("2 de 3 marcados").assertIsDisplayed()
        // A própria rotina sai da etiqueta: só aparece com quem mais a lista é compartilhada.
        compose.onNodeWithText("Também em: Churrasco").assertIsDisplayed()

        compose.onNodeWithText("Mercado").performClick()
        compose.onNodeWithContentDescription("Desvincular lista").performClick()

        assertEquals(1, opened)
        assertEquals(1, detached)
    }
}
