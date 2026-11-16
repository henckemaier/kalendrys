package com.kalendrys.app.ui.categories

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Linha de categoria (ADR-0002, 12.4): toque edita; Excluir só no menu de três pontos. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CategoryRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun setRow() {
        compose.setContent {
            KalendrysTheme {
                CategoryRow(
                    category = RoutineCategory(id = 1, name = "Academia", color = "GREEN"),
                    onEdit = { calls += "edit" },
                    onDelete = { calls += "delete" },
                )
            }
        }
    }

    @Test
    fun `toque na linha edita, com o rotulo Editar categoria`() {
        setRow()

        val node = compose.onNodeWithText("Academia").fetchSemanticsNode()
        assertEquals("Editar Academia", node.config[SemanticsActions.OnClick].label)
        compose.onNodeWithText("Academia").performClick()

        compose.runOnIdle { assertEquals(listOf("edit"), calls) }
    }

    @Test
    fun `sem botoes soltos de editar e excluir na linha`() {
        setRow()

        listOf("Editar Academia", "Excluir Academia").forEach { label ->
            val buttons = compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes()
            assertEquals(label, 0, buttons.size)
        }
    }

    @Test
    fun `menu de tres pontos tem Excluir e chama onDelete`() {
        setRow()

        compose.onNodeWithContentDescription("Mais opções para Academia").performClick()
        compose.onNode(hasText("Excluir")).performClick()

        compose.runOnIdle { assertEquals(listOf("delete"), calls) }
    }
}
