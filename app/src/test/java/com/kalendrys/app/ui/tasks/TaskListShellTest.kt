package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.navigation.UndoOfferEffect
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Aba Tarefas na casca da v1.7.0 (ADR-0002, 12.2, E3): o deslize de excluir passou para o
 * SwipeRow mantendo o comportamento (só para a esquerda, Desfazer no snackbar, ação "Excluir
 * tarefa" para o leitor de tela), e a barra de topo e o FAB são os componentes compartilhados.
 *
 * v1.8.0 (E5): o Desfazer de excluir e de concluir pela lista é a oferta do APP (UndoOffers), com o
 * coletor único fora da tela, como no KalendrysNavHost; girar a tela com a oferta pendente mostra a
 * mensagem de novo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskListShellTest {

    private val newTaskRequests = mutableListOf<java.time.LocalDate>()

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TaskViewModel
    private lateinit var offers: UndoOffers

    /** Trocar a geração descarta o coletor, o estado da mensagem e a tela, como girar (o ViewModel fica). */
    private var generation by mutableIntStateOf(0)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        runBlocking { db.taskDao().insert(Task(title = "Comprar pão")) }
        val repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        offers = testUndoOffers(testDb, tasks = repository)
        viewModel = testDb.track(
            TaskViewModel(
                repository,
                ShoppingRepository(db),
                offers,
            ),
        )
        compose.setContent {
            KalendrysTheme {
                key(generation) {
                    // Como o KalendrysNavHost: a mensagem do app, com o coletor único fora da tela.
                    val app = remember { SnackbarHostState() }
                    CompositionLocalProvider(LocalAppSnackbarHostState provides app) {
                        UndoOfferEffect(offers, app)
                        TaskListScreen(onOpenTask = {}, onOpenSettings = {}, onNewTask = { newTaskRequests += it }, viewModel = viewModel)
                    }
                }
            }
        }
        waitForText("Comprar pão")
    }

    @After
    fun tearDown() = testDb.close()

    private fun waitForText(text: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoText(text: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun rowActions() =
        compose.onNodeWithText("Comprar pão").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()

    @Test
    fun `a linha oferece so Excluir tarefa ao leitor de tela e a acao exclui com Desfazer`() {
        assertEquals(listOf("Excluir tarefa"), rowActions().map { it.label })

        compose.runOnIdle { rowActions().single().action() }

        waitForText("Tarefa excluída")
        waitForNoText("Comprar pão")
        compose.onNodeWithText("Desfazer").performClick()
        waitForText("Comprar pão")
    }

    @Test
    fun `deslizar para a esquerda exclui e o Desfazer devolve a linha visivel`() {
        compose.onNodeWithText("Comprar pão").performTouchInput { swipeLeft() }

        waitForText("Tarefa excluída")
        waitForNoText("Comprar pão")
        compose.onNodeWithText("Desfazer").performClick()
        waitForText("Comprar pão")
        // Estado do gesto novo: a linha devolvida está no lugar, não fora da tela.
        val left = compose.onNodeWithText("Comprar pão", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertTrue("linha devolvida fora da tela (left=$left)", left >= 0f)
    }

    /** Mesmo limiar de antes (40% da largura): um deslize lento curto volta sem excluir. */
    @Test
    fun `deslize lento de 30 por cento para a esquerda nao exclui`() {
        compose.onNodeWithText("Comprar pão").performTouchInput {
            swipeLeft(startX = right, endX = right - width * 0.3f, durationMillis = 2_000)
        }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        compose.onNodeWithText("Comprar pão").assertHasClickAction()
        assertEquals(1, runBlocking { testDb.db.taskDao().all() }.size)
    }

    @Test
    fun `deslizar para a direita nao faz nada, como antes`() {
        compose.onNodeWithText("Comprar pão").performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithText("Comprar pão").assertHasClickAction()
        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
    }

    @Test
    fun `barra de topo com Configuracoes e FAB Nova tarefa`() {
        compose.onNodeWithContentDescription("Configurações").assertHasClickAction()
        compose.onNodeWithContentDescription("Nova tarefa").performClick()

        // O painel mora no topo da navegação (E6): o FAB só pede, sugerindo hoje.
        compose.waitForIdle()
        assertEquals(listOf(java.time.LocalDate.now()), newTaskRequests)
    }

    // --- E5 (v1.8.0): Desfazer da lista pela oferta do app ---

    private fun completeButton() = compose.onNode(hasContentDescription("Concluir tarefa") and hasClickAction())

    private fun waitForDescription(description: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }

    private fun completions(): Int = runBlocking { testDb.db.completionLogDao().all().size }

    /** Como girar: a tela, o coletor e o estado da mensagem saem e voltam novos; o ViewModel e a oferta ficam. */
    private fun recreate() {
        compose.runOnUiThread { generation++ }
        compose.waitForIdle()
    }

    @Test
    fun `concluir pelo circulo publica a oferta do app e o Desfazer apaga o registro e devolve a pendente`() {
        completeButton().performClick()

        waitForText("Tarefa concluída")
        waitForDescription("Concluída hoje")
        compose.runOnIdle { assertTrue(offers.pending.value?.target is UndoTarget.CompletedTask) }
        assertEquals(1, completions())

        compose.onNode(hasText("Desfazer") and hasClickAction()).performClick()

        waitForDescription("Concluir tarefa")
        compose.waitUntil(TIMEOUT_MS) { completions() == 0 }
        assertNull(offers.pending.value)
    }

    @Test
    fun `excluir publica uma mensagem so na lista, a do app`() {
        compose.onNodeWithText("Comprar pão").performTouchInput { swipeLeft() }

        waitForText("Tarefa excluída")
        compose.runOnIdle { assertTrue(offers.pending.value?.target is UndoTarget.DeletedTask) }
        assertEquals(1, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("Desfazer") and hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `girar com a exclusao pendente mostra a mensagem de novo e o Desfazer devolve a tarefa`() {
        compose.onNodeWithText("Comprar pão").performTouchInput { swipeLeft() }
        waitForText("Tarefa excluída")
        waitForNoText("Comprar pão")

        recreate()

        waitForText("Tarefa excluída")
        assertEquals(1, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        compose.onNode(hasText("Desfazer") and hasClickAction()).performClick()
        waitForText("Comprar pão")
        assertNull(offers.pending.value)
    }

    @Test
    fun `girar com a conclusao pendente mostra a mensagem de novo e o Desfazer reabre a tarefa`() {
        completeButton().performClick()
        waitForText("Tarefa concluída")

        recreate()

        waitForText("Tarefa concluída")
        compose.onNode(hasText("Desfazer") and hasClickAction()).performClick()
        waitForDescription("Concluir tarefa")
        compose.waitUntil(TIMEOUT_MS) { completions() == 0 }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
