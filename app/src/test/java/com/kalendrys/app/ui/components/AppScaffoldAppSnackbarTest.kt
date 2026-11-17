package com.kalendrys.app.ui.components

import android.os.Looper
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Mensagem do app no AppScaffold (v1.8.0, E0-D): com o estado fornecido, a tela à frente desenha a
 * mensagem do app acima da dela e o FAB sobe sobre as duas; sem fornecer, nada muda.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class AppScaffoldAppSnackbarTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = SnackbarHostState()
    private val own = SnackbarHostState()
    private val scope = CoroutineScope(Dispatchers.Main)

    @Composable
    private fun Screen(ownHost: SnackbarHostState? = own) {
        AppScaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = {}, modifier = Modifier.testTag("fab")) { Text("+") }
            },
            hasFab = true,
            snackbarHostState = ownHost,
        ) {}
    }

    // Relógio parado: a mensagem longa (10 s) não pode vencer enquanto o teste confere.
    private fun advance() {
        compose.mainClock.autoAdvance = false
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun `mensagem do app fica acima da mensagem da tela e nenhuma cobre o FAB`() {
        compose.setContent {
            KalendrysTheme {
                CompositionLocalProvider(LocalAppSnackbarHostState provides app) { Screen() }
            }
        }

        scope.launch { app.showUndo("Tarefa excluída", "Desfazer") }
        scope.launch { own.showUndo("Tarefa concluída", "Desfazer") }
        advance()

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Tarefa concluída").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("Desfazer").fetchSemanticsNodes().size)
        val appBounds = compose.onNodeWithText("Tarefa excluída").fetchSemanticsNode().boundsInRoot
        val ownBounds = compose.onNodeWithText("Tarefa concluída").fetchSemanticsNode().boundsInRoot
        assertTrue("uma acima da outra: $appBounds / $ownBounds", appBounds.bottom <= ownBounds.top)
        // Material3: o slot de mensagens fica ACIMA do FAB (como nas telas hoje); nenhuma o cobre.
        val fab = compose.onNodeWithTag("fab").fetchSemanticsNode().boundsInRoot
        listOf(appBounds, ownBounds).forEach { message ->
            assertTrue("mensagem $message sobre o FAB $fab", message.bottom <= fab.top)
        }
    }

    @Test
    fun `sem fornecer o estado do app a tela funciona como antes`() {
        compose.setContent { KalendrysTheme { Screen() } }

        scope.launch { own.showUndo("Tarefa concluída", "Desfazer") }
        advance()

        compose.onNodeWithText("Tarefa concluída").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Desfazer").fetchSemanticsNodes().size)
    }

    @Test
    fun `tela sem mensagem propria tambem mostra a do app`() {
        compose.setContent {
            KalendrysTheme {
                CompositionLocalProvider(LocalAppSnackbarHostState provides app) { Screen(ownHost = null) }
            }
        }

        scope.launch { app.showUndo("Tarefa excluída", "Desfazer") }
        advance()

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
    }

    @Test
    fun `tela que nao esta a frente nao desenha a mensagem do app`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        // Como a origem e o detalhe durante a transição de volta: compostos, mas não RESUMED.
        owner.registry.currentState = Lifecycle.State.STARTED
        compose.setContent {
            KalendrysTheme {
                CompositionLocalProvider(
                    LocalAppSnackbarHostState provides app,
                    LocalLifecycleOwner provides owner,
                ) { Screen() }
            }
        }

        scope.launch { app.showUndo("Tarefa excluída", "Desfazer") }
        advance()
        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)

        // Ao chegar à frente, a mensagem aparece.
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
    }
}
