package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
    fun `FAB aberto e lido uma vez so, pela descricao (o ExtendedFAB tira o texto da semantica)`() {
        compose.setContent {
            KalendrysTheme { CollapsingFab(text = "Nova lista", expanded = true, onClick = {}) }
        }

        compose.onNodeWithContentDescription("Nova lista").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Nova lista").fetchSemanticsNodes().size)
    }

    @Test
    fun `em tela larga o excedente de 640dp vai para o preenchimento lateral`() {
        var padding: PaddingValues? = null
        compose.setContent {
            KalendrysTheme {
                Box(Modifier.requiredSize(width = 1000.dp, height = 400.dp)) {
                    AppScaffold { padding = it }
                }
            }
        }
        compose.waitForIdle()

        // (1000 - 640) / 2 = 180dp de cada lado; sem insets de sistema no teste.
        assertEquals(180.dp, checkNotNull(padding).calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(180.dp, checkNotNull(padding).calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `em telefone nao ha excedente`() {
        var padding: PaddingValues? = null
        compose.setContent {
            KalendrysTheme {
                Box(Modifier.requiredSize(width = 400.dp, height = 400.dp)) {
                    AppScaffold(hasFab = true) { padding = it }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(0.dp, checkNotNull(padding).calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(FabClearance, checkNotNull(padding).calculateBottomPadding())
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

    @Test
    fun `acao prominent aparece como botao de texto na barra, respeitando enabled`() {
        var saves = 0
        var enabled by mutableStateOf(false)
        compose.setContent {
            KalendrysTheme {
                AppTopBar(
                    title = "Editar tarefa",
                    onBack = {},
                    actions = listOf(TopBarAction("Salvar", enabled = enabled, prominent = true) { saves++ }),
                    menu = listOf(TopBarAction("Excluir tarefa") {}),
                )
            }
        }

        // Na barra, escrito (não no menu), e desligado enquanto não pode salvar.
        compose.onNodeWithText("Salvar").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, compose.onAllNodesWithText("Excluir tarefa").fetchSemanticsNodes().size)

        enabled = true
        compose.onNodeWithText("Salvar").assertIsEnabled().performClick()

        assertEquals(1, saves)
    }
}
