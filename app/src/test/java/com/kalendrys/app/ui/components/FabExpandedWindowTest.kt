package com.kalendrys.app.ui.components

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [rememberFabExpanded] com a altura de verdade da janela (B-1 da E3): o [fabExpanded] puro já tem
 * teste; aqui vale a ligação com o LocalWindowInfo e a conversão de px para dp (em px, 360dp de
 * altura passariam dos 480 e o FAB abriria em paisagem). Recolhido = só o ícone, 56dp de largura.
 */
@RunWith(AndroidJUnit4::class)
class FabExpandedWindowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fabWidth(): Float {
        compose.setContent {
            KalendrysTheme {
                val expanded by rememberFabExpanded(rememberLazyListState())
                CollapsingFab(text = "Nova lista", expanded = expanded, onClick = {})
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithContentDescription("Nova lista").getBoundsInRoot().let { it.right - it.left }.value
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
    fun `paisagem de telefone com 360dp de altura nasce recolhido mesmo no topo`() {
        assertEquals(56.dp.value, fabWidth(), 0.5f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h479dp-land-xxhdpi")
    fun `um dp abaixo do corte fica recolhido`() {
        assertEquals(56.dp.value, fabWidth(), 0.5f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-port-xxhdpi")
    fun `retrato alto no topo da lista abre o FAB`() {
        assertTrue("FAB deveria estar aberto (largura > 56dp)", fabWidth() > 56.dp.value + 1f)
    }
}
