package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Linha de item da lista (ADR-0002, 12.4): ações do deslize para o leitor de tela, toque edita. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingItemSwipeRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var item by mutableStateOf(ShoppingItem(id = 1, listId = 1, name = "Arroz", quantity = 2, position = 0))

    private fun setRow() {
        compose.setContent {
            KalendrysTheme {
                ShoppingItemSwipeRow(
                    item = item,
                    index = 0,
                    count = 1,
                    onToggle = { calls += "toggle" },
                    onRemove = { calls += "remove" },
                    onEdit = { calls += "edit" },
                    modifier = Modifier.width(360.dp),
                )
            }
        }
    }

    private fun rowConfig() = compose.onNodeWithText("Arroz").fetchSemanticsNode().config

    private fun actionLabels() = rowConfig().getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun `item nao marcado oferece Marcar como pego e Remover item`() {
        setRow()

        assertEquals(listOf("Marcar como pego", "Remover item"), actionLabels())
    }

    @Test
    fun `item marcado troca o rotulo para Desmarcar item`() {
        item = item.copy(picked = true)
        setRow()

        assertEquals(listOf("Desmarcar item", "Remover item"), actionLabels())
    }

    @Test
    fun `as acoes de acessibilidade disparam os callbacks`() {
        setRow()

        compose.runOnIdle {
            rowConfig()[SemanticsActions.CustomActions].forEach { it.action() }
        }

        compose.runOnIdle { assertEquals(listOf("toggle", "remove"), calls) }
    }

    @Test
    fun `toque na linha edita, com o rotulo Editar item`() {
        setRow()

        assertEquals("Editar item", rowConfig()[SemanticsActions.OnClick].label)
        compose.onNodeWithText("Arroz").performClick()

        compose.runOnIdle { assertEquals(listOf("edit"), calls) }
    }

    @Test
    fun `estado vai como stateDescription no no da linha e o toque continua Editar`() {
        setRow()
        assertEquals("Não marcado", rowConfig()[SemanticsProperties.StateDescription])
        assertEquals("Editar item", rowConfig()[SemanticsActions.OnClick].label)
        // A linha não vira caixa de marcar.
        assertNull(rowConfig().getOrNull(SemanticsProperties.ToggleableState))
        assertNull(rowConfig().getOrNull(SemanticsProperties.Role))

        item = item.copy(picked = true)
        compose.waitForIdle()
        assertEquals("Marcado", rowConfig()[SemanticsProperties.StateDescription])
        assertEquals("Editar item", rowConfig()[SemanticsActions.OnClick].label)

        compose.onNodeWithText("Arroz").performClick()
        compose.runOnIdle { assertEquals(listOf("edit"), calls) }
    }

    @Test
    fun `deslizar para a direita marca e para a esquerda remove`() {
        setRow()

        compose.onNodeWithText("Arroz").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Arroz").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("toggle", "remove"), calls)
    }
}
