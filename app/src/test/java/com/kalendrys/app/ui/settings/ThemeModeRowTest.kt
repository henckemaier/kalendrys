package com.kalendrys.app.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Seletor de tema (D-1 da Coruja na E1): com fonte 2.0 em retrato o botão segmentado cortava
 * "Sistema". Com fonte grande vira lista de rádio, com os três rótulos inteiros.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class ThemeModeRowTest {

    @get:Rule
    val compose = createComposeRule()

    private var chosen: ThemeMode? = null

    private fun render(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    ThemeModeRow(selected = ThemeMode.SYSTEM, onSelect = { chosen = it }, shape = RoundedCornerShape(0))
                }
            }
        }
    }

    private fun top(label: String): Float =
        compose.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun `fonte 2_0 mostra os tres rotulos inteiros como opcoes de radio`() {
        render(fontScale = 2f)

        listOf("Sistema", "Claro", "Escuro").forEach { label ->
            assertEquals(1, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
        }
        assertEquals(3, compose.onAllNodes(radio).fetchSemanticsNodes().size)
        // Uma opção por linha: cada rótulo tem a largura toda.
        assertTrue(top("Sistema") < top("Claro") && top("Claro") < top("Escuro"))
        compose.onNodeWithText("Sistema").assertIsSelected()

        compose.onNodeWithText("Escuro").performClick()
        assertEquals(ThemeMode.DARK, chosen)
    }

    @Test
    fun `fonte normal continua com o botao segmentado`() {
        render(fontScale = 1f)

        // As três lado a lado, na mesma linha.
        assertEquals(top("Sistema"), top("Claro"))
        assertEquals(top("Claro"), top("Escuro"))
        compose.onNodeWithText("Claro").performClick()
        assertEquals(ThemeMode.LIGHT, chosen)
    }

    /**
     * Os dois testes acima (1.0 e 2.0) passariam com o corte em qualquer ponto entre eles. As escalas
     * do sistema perto do corte são 1.3 e 1.5: "acima de 1.3" deixa 1.3 no segmentado e 1.5 no rádio.
     */
    @Test
    fun `fonte 1_3 ainda e segmentado`() {
        render(fontScale = 1.3f)

        // Lado a lado (o SegmentedButton também se anuncia como rádio, então a posição é que decide).
        assertEquals(top("Sistema"), top("Claro"))
        assertEquals(top("Claro"), top("Escuro"))
    }

    @Test
    fun `fonte 1_5 ja vira lista de radio`() {
        render(fontScale = 1.5f)

        assertEquals(3, compose.onAllNodes(radio).fetchSemanticsNodes().size)
        assertTrue(top("Sistema") < top("Claro") && top("Claro") < top("Escuro"))
    }
}
