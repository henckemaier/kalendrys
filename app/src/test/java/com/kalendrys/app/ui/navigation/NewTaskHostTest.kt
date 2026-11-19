package com.kalendrys.app.ui.navigation

import android.content.Context
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.NewTaskPanel
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testTaskRepository
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Painel de nova tarefa num lugar só (v1.8.0, E6, ADR-0002 14.7): o [NewTaskHost] no topo da navegação
 * abre o painel POR CIMA de qualquer destino, com o aberto/fechado salvo pela tela e o rascunho no app.
 * Aqui com um NavHost de teste (as fábricas das telas reais pedem o app inteiro), no molde do
 * [LegacyNavStateRestoreTest]. O voltar preditivo e o teclado ficam para a conferência no emulador.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NewTaskHostTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var panel: NewTaskPanel
    private val today = LocalDate.now()

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(ApplicationProvider.getApplicationContext<Context>())
        panel = NewTaskPanel(testTaskRepository(testDb.db), ShoppingRepository(testDb.db), testDb.appScope(), NewTaskDraftStore())
    }

    @After
    fun tearDown() = testDb.close()

    /** Muda o aberto/fechado, que mora SÓ no rememberSaveable do shell, como no KalendrysNavHost. */
    private lateinit var setOpenOn: (Long?) -> Unit

    private lateinit var navController: NavHostController

    /** Abas e destinos de teste, com o host do painel por cima, como no KalendrysNavHost. */
    private fun shell(tester: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            KalendrysTheme {
                navController = rememberNavController()
                var openOn by rememberSaveable { mutableStateOf<Long?>(null) }
                setOpenOn = { openOn = it }
                NavHost(navController, startDestination = "aba") {
                    composable("aba") { BasicText("tela da aba") }
                    composable("detalhe") { BasicText("tela do detalhe") }
                    composable("editor") {
                        var text by rememberSaveable { mutableStateOf("") }
                        TextField(value = text, onValueChange = { text = it }, label = { Text("Nome da rotina") })
                    }
                    composable("dialogo") {
                        AlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                            text = { Text("diálogo aberto") },
                        )
                    }
                }
                NewTaskHost(panel, openOn?.let(LocalDate::ofEpochDay), onClose = { openOn = null })
            }
        }
        if (tester != null) tester.setContent(content) else compose.setContent(content)
        compose.waitForIdle()
    }

    private fun navigate(route: String) {
        compose.runOnUiThread { navController.navigate(route) }
        compose.waitForIdle()
    }

    private fun open(date: LocalDate = today) {
        compose.runOnUiThread { setOpenOn(date.toEpochDay()) }
        compose.waitForIdle()
    }

    /**
     * Fecha como o onDismiss do painel (voltar, tocar fora, arrastar). O Cancelar e o Salvar do painel
     * escondem a janela com animação antes de avisar, e essa animação não termina no Robolectric: eles
     * ficam para o emulador; aqui o que se prova é o host.
     */
    private fun close() {
        compose.runOnUiThread { setOpenOn(null) }
        compose.waitForIdle()
    }

    private fun panelCount() = compose.onAllNodesWithText("Título").fetchSemanticsNodes().size

    @Test
    fun `abre por cima do detalhe e fechar deixa o detalhe a frente`() {
        shell()
        navigate("detalhe")

        open()

        assertEquals(1, panelCount())
        close()
        assertEquals(0, panelCount())
        assertEquals("detalhe", navController.currentDestination?.route)
        compose.onNodeWithText("tela do detalhe").fetchSemanticsNode()
    }

    @Test
    fun `por cima do editor com alteracao nao salva, fechar o painel deixa o editor intacto`() {
        shell()
        navigate("editor")
        compose.onNodeWithText("Nome da rotina").performTextInput("Treino de sábado")

        open()
        compose.onNodeWithText("Título").performTextInput("Comprar pão")
        close()

        assertEquals("editor", navController.currentDestination?.route)
        compose.onNodeWithText("Treino de sábado").fetchSemanticsNode()
    }

    @Test
    fun `por cima de um dialogo aberto, fechar o painel deixa o dialogo`() {
        shell()
        navigate("dialogo")

        open()
        assertEquals(1, panelCount())
        close()

        compose.onNodeWithText("diálogo aberto").fetchSemanticsNode()
    }

    @Test
    fun `pedido repetido com o painel aberto nao cria outro nem apaga o rascunho`() {
        shell()
        open()
        compose.onNodeWithText("Título").performTextInput("Pagar luz")

        // Widget tocado de novo: o mesmo pedido, e um pedido com outro dia sugerido.
        open()
        open(today.plusDays(2))

        assertEquals(1, panelCount())
        compose.onAllNodesWithText("Pagar luz").assertCountEquals(1)
        assertEquals("Pagar luz", panel.draft.value?.title)
    }

    @Test
    fun `fechar guarda o rascunho e reabrir de outro destino mostra o mesmo`() {
        shell()
        open()
        compose.onNodeWithText("Título").performTextInput("Ligar para o banco")
        // Fechar (tocar fora, voltar, arrastar) é o onDismiss do painel: guarda.
        close()
        assertEquals(0, panelCount())

        navigate("detalhe")
        open(today.plusDays(1))

        compose.onNodeWithText("Ligar para o banco").fetchSemanticsNode()
    }

    @Test
    fun `girar com o painel aberto reabre o painel com o rascunho`() {
        val tester = StateRestorationTester(compose)
        shell(tester)
        open()
        compose.onNodeWithText("Título").performTextInput("Regar as plantas")

        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(1, panelCount())
        compose.onNodeWithText("Regar as plantas").fetchSemanticsNode()
    }
}
