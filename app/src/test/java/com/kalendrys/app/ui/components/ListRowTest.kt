package com.kalendrys.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [ListRow] (ADR-0002, 4.2): toque na linha, semântica mesclada e alvo mínimo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ListRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `toque em qualquer parte da linha chama onClick uma vez`() {
        var clicks = 0
        compose.setContent {
            KalendrysTheme {
                ListRow(title = "Mercado", supporting = "3 de 8 marcados", onClick = { clicks++ }, onClickLabel = "Abrir lista")
            }
        }

        compose.onNodeWithText("3 de 8 marcados").performClick()

        assertEquals(1, clicks)
        val node = compose.onNodeWithText("Mercado").assertHasClickAction().fetchSemanticsNode()
        assertEquals("Abrir lista", node.config[SemanticsActions.OnClick].label)
    }

    @Test
    fun `semantica mesclada le titulo, apoio e icone como um item so`() {
        compose.setContent {
            KalendrysTheme {
                ListRow(
                    title = "Mercado",
                    supporting = "3 de 8 marcados",
                    leading = { Icon(Icons.Outlined.ShoppingCart, contentDescription = "Lista de compras") },
                    onClick = {},
                )
            }
        }

        // O nó com o título (árvore mesclada) também carrega o apoio e o ícone.
        val merged = compose.onNodeWithText("Mercado").fetchSemanticsNode()
        assertEquals(listOf("Mercado", "3 de 8 marcados"), merged.config[SemanticsProperties.Text].map { it.text })
        assertEquals(listOf("Lista de compras"), merged.config.getOrNull(SemanticsProperties.ContentDescription))
        compose.onNodeWithText("3 de 8 marcados").assertHasClickAction()
    }

    @Test
    fun `linha sem onClick nao e clicavel e ainda tem o alvo minimo`() {
        compose.setContent {
            KalendrysTheme { ListRow(title = "Só título") }
        }

        compose.onNodeWithText("Só título")
            .assertHasNoClickAction()
            .assertHeightIsAtLeast(ListRowMinHeight)
    }
}
