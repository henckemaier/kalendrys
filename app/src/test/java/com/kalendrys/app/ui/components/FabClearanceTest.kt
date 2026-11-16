package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Folga do FAB limitada à área útil (S-1 da Coruja): com cabeçalho alto em paisagem a lista não pode
 * rolar até o último item sumir sob a barra; sempre sobra uma linha (48dp) visível.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FabClearanceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `com espaco de sobra vale a folga inteira`() {
        assertEquals(FabClearance, fabClearanceFor(600.dp))
    }

    @Test
    fun `com pouco espaco a folga deixa uma linha visivel e nunca fica negativa`() {
        assertEquals(52.dp, fabClearanceFor(100.dp))
        assertEquals(0.dp, fabClearanceFor(40.dp))
    }

    @Test
    fun `aba com cabecalho alto em paisagem tem a folga limitada`() {
        var padding: PaddingValues? = null
        compose.setContent {
            KalendrysTheme {
                // Janela de paisagem baixa e barra com faixa e filtros (como a aba Rotinas): 300dp.
                Box(Modifier.requiredSize(width = 800.dp, height = 400.dp)) {
                    AppScaffold(
                        topBar = { Box(Modifier.fillMaxWidth().height(300.dp)) },
                        hasFab = true,
                    ) { padding = it }
                }
            }
        }
        compose.waitForIdle()

        // Sobram 100dp entre a barra e o fim: a folga fica em 100 - 48, não nos 104 de sempre.
        assertEquals(52.dp, checkNotNull(padding).calculateBottomPadding())
    }

    @Test
    fun `telefone em retrato mantem a folga inteira`() {
        var padding: PaddingValues? = null
        compose.setContent {
            KalendrysTheme {
                Box(Modifier.requiredSize(width = 400.dp, height = 800.dp)) {
                    AppScaffold(topBar = { Box(Modifier.fillMaxWidth().height(64.dp)) }, hasFab = true) { padding = it }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(FabClearance, checkNotNull(padding).calculateBottomPadding())
    }
}
