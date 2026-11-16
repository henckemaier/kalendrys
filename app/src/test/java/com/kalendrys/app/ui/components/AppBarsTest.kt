package com.kalendrys.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** AppTopBar e CollapsingFab (ADR-0002, 4.2): rótulos para o leitor de tela e menu com rótulo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppBarsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `FAB recolhido continua com o rotulo para o leitor de tela`() {
        var clicks = 0
        compose.setContent {
            KalendrysTheme { CollapsingFab(text = "Nova lista", expanded = false, onClick = { clicks++ }) }
        }

        compose.onNodeWithContentDescription("Nova lista").assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `barra de topo com voltar, acao em icone e acoes raras no menu`() {
        var back = 0
        var settings = 0
        var rename = 0
        compose.setContent {
            KalendrysTheme {
                AppTopBar(
                    title = "Mercado",
                    onBack = { back++ },
                    actions = listOf(TopBarAction("Configurações", Icons.Outlined.Settings) { settings++ }),
                    menu = listOf(TopBarAction("Renomear") { rename++ }),
                )
            }
        }

        compose.onNodeWithText("Mercado").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithContentDescription("Configurações").performClick()
        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNodeWithText("Renomear").performClick()

        assertEquals(listOf(1, 1, 1), listOf(back, settings, rename))
    }
}
