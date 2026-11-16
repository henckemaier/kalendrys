package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Causa do R-1 da E4 (5d529c7): o Scaffold reaproveita o MESMO objeto de preenchimento e só troca os
 * valores, então uma cópia guardada em `remember` com ele de chave ficava velha. Aqui o rodapé (como o
 * campo de adição rápida, que só aparece depois de carregar) e a barra do topo mudam depois da primeira
 * composição, e o preenchimento entregue ao conteúdo tem de acompanhar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppScaffoldPaddingTest {

    @get:Rule
    val compose = createComposeRule()

    private var bottomBarHeight by mutableStateOf(0.dp)
    private var topBarHeight by mutableStateOf(64.dp)
    private var top: Dp = (-1).dp
    private var bottom: Dp = (-1).dp

    private fun show(hasFab: Boolean) {
        compose.setContent {
            KalendrysTheme {
                Box(Modifier.requiredSize(width = 400.dp, height = 800.dp)) {
                    AppScaffold(
                        topBar = { Box(Modifier.fillMaxWidth().height(topBarHeight)) },
                        bottomBar = {
                            if (bottomBarHeight > 0.dp) Box(Modifier.fillMaxWidth().height(bottomBarHeight))
                        },
                        hasFab = hasFab,
                    ) { padding ->
                        top = padding.calculateTopPadding()
                        bottom = padding.calculateBottomPadding()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `rodape que aparece depois de carregar entra no fundo do conteudo e sai quando some`() {
        show(hasFab = false)
        val base = bottom
        assertEquals(64.dp, top)

        bottomBarHeight = 72.dp
        compose.waitForIdle()
        assertEquals(base + 72.dp, bottom)

        bottomBarHeight = 0.dp
        compose.waitForIdle()
        assertEquals(base, bottom)
    }

    @Test
    fun `barra do topo que cresce depois atualiza o topo e a folga do FAB`() {
        show(hasFab = true)
        assertEquals(64.dp, top)
        assertEquals(FabClearance, bottom)

        // Cabeçalho alto, como a aba Rotinas em paisagem: sobram 100dp e a folga cai para 100 - 48.
        topBarHeight = 700.dp
        compose.waitForIdle()
        assertEquals(700.dp, top)
        assertEquals(52.dp, bottom)
    }
}
