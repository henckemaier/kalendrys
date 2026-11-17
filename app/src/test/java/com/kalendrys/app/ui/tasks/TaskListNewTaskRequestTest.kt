package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Último trecho do `LaunchRequest.NewTask` (widget e atalho "Nova tarefa", ADR-0002, 12.6): o
 * NavHost abre a aba Tarefas e passa `openNewTask = true`; a tela abre o painel de nova tarefa e
 * avisa que consumiu o pedido, para ele não reabrir ao voltar à aba.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskListNewTaskRequestTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TaskViewModel
    private val store = NewTaskDraftStore()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        viewModel = testDb.track(
            TaskViewModel(
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao()),
                ShoppingRepository(db),
                store,
            ),
        )
    }

    @After
    fun tearDown() = testDb.close()

    private fun show(openNewTask: Boolean, onNewTaskOpened: () -> Unit) {
        compose.setContent {
            KalendrysTheme {
                TaskListScreen(
                    onOpenTask = {},
                    onOpenSettings = {},
                    openNewTask = openNewTask,
                    onNewTaskOpened = onNewTaskOpened,
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun `pedido de nova tarefa abre o painel e e consumido uma vez`() {
        var consumed = 0
        show(openNewTask = true) { consumed++ }

        // "Título" é o campo do painel; o "Nova tarefa" do botão flutuante está sempre na tela.
        compose.onNodeWithText("Título").assertIsDisplayed()
        assertEquals(1, consumed)
    }

    @Test
    fun `sem pedido o painel fica fechado`() {
        var consumed = 0
        show(openNewTask = false) { consumed++ }

        compose.onAllNodesWithText("Título").assertCountEquals(0)
        assertEquals(0, consumed)
    }

    @Test
    fun `atalho com rascunho guardado mostra o rascunho`() {
        store.open(java.time.LocalDate.now())
        store.update { it.copy(title = "Pagar luz") }

        show(openNewTask = true) {}

        compose.onNode(hasSetTextAction() and hasText("Pagar luz")).assertIsDisplayed()
    }

    @Test
    fun `o que se digita no painel vai para o rascunho`() {
        show(openNewTask = true) {}

        compose.onNode(hasSetTextAction() and hasText("Título")).performTextReplacement("Comprar pão")
        compose.waitForIdle()

        assertEquals("Comprar pão", store.draft.value!!.title)
    }
}
