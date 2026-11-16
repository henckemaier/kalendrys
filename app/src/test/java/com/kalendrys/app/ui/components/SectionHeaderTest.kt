package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `SectionHeader` (ADR-0002, 4.2): é cabeçalho para o leitor de tela e lê título e contagem juntos. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SectionHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `titulo com contagem e um cabecalho so, lido junto`() {
        compose.setContent {
            KalendrysTheme {
                Column {
                    SectionHeader(title = "Pendentes")
                    SectionHeader(title = "Marcados", count = 3)
                }
            }
        }

        compose.onNodeWithText("Pendentes").assert(isHeading)
        compose.onNodeWithText("Marcados").assert(isHeading).assertTextEquals("Marcados", "3")
        compose.onAllNodes(isHeading).assertCountEquals(2)
    }

    /**
     * C2 (b4292f7): o [SectionHeader] ganhou onClick/trailing. Sem onClick (Pendentes, Configurações)
     * continua só cabeçalho, sem ação de toque; com onClick, a ação, o rótulo e o ícone da ponta ficam
     * num nó só, o mesmo do cabeçalho.
     */
    @Test
    fun `sem onClick nao ganha toque e com onClick a acao fica no mesmo no do cabecalho`() {
        var clicks = 0
        compose.setContent {
            KalendrysTheme {
                Column {
                    SectionHeader(title = "Pendentes", count = 2)
                    SectionHeader(
                        title = "Marcados",
                        count = 3,
                        onClick = { clicks++ },
                        onClickLabel = "Recolher marcados",
                        trailing = { Icon(Icons.Outlined.ExpandLess, contentDescription = null) },
                    )
                }
            }
        }

        val plain = compose.onNodeWithText("Pendentes").fetchSemanticsNode().config
        assertNull(plain.getOrNull(SemanticsActions.OnClick))
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)

        val group = compose.onNodeWithText("Marcados").assert(isHeading).assertTextEquals("Marcados", "3")
        assertEquals("Recolher marcados", group.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onAllNodes(isHeading).assertCountEquals(2)

        group.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
