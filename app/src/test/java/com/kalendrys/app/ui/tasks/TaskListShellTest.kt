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
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskListShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TaskViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        runBlocking { db.taskDao().insert(Task(title = "Comprar pão")) }
        viewModel = testDb.track(
            TaskViewModel(
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao()),
                ShoppingRepository(db),
            ),
        )
        compose.setContent {
            KalendrysTheme {
                TaskListScreen(onOpenTask = {}, onOpenSettings = {}, viewModel = viewModel)
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

        // O FAB abre o painel de nova tarefa, como antes.
        waitForText("Título")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
