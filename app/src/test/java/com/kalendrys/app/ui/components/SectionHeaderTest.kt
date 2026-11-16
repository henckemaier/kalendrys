package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
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
}
