package com.kalendrys.app.ui.taskdetail

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Detalhe da tarefa na casca da v1.7.0 (ADR-0002, 12.2, E3): AppTopBar com voltar, "Salvar" escrito
 * na barra (ligado só com alteração) e "Excluir tarefa" no menu com rótulo, abrindo a confirmação
 * de sempre.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskDetailShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private var backs = 0
    private var taskId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        taskId = runBlocking { db.taskDao().insert(Task(title = "Comprar pão")) }
        val viewModel = testDb.track(
            TaskDetailViewModel(
                SavedStateHandle(mapOf(TASK_ID_ARG to taskId)),
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao()),
                ShoppingRepository(db),
            ),
        )
        compose.setContent {
            KalendrysTheme { TaskDetailScreen(onBack = { backs++ }, viewModel = viewModel) }
        }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Comprar pão").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `Salvar fica escrito na barra e liga so depois de uma alteracao`() {
        compose.onNodeWithText("Detalhes da tarefa").assertIsDisplayed()
        compose.onNodeWithText("Salvar").assertIsDisplayed().assertIsNotEnabled()

        compose.onNodeWithText("Comprar pão").performTextInput(" integral")

        compose.waitForIdle()
        compose.onNodeWithText("Salvar").assertIsEnabled()
    }

    @Test
    fun `Excluir tarefa sai do icone solto para o menu com rotulo e pede confirmacao`() {
        assertEquals(0, compose.onAllNodesWithText("Excluir tarefa").fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNodeWithText("Excluir tarefa").performClick()

        compose.onNodeWithText("Excluir tarefa?").assertIsDisplayed()
    }

    /** O "Salvar" agora é o TextButton da barra (TopBarAction.prominent): o toque tem de gravar. */
    @Test
    fun `tocar Salvar na barra grava a alteracao e o botao desliga de novo`() {
        compose.onNodeWithText("Comprar pão").performTextReplacement("Comprar pão integral")
        compose.waitForIdle()

        compose.onNodeWithText("Salvar").assertIsEnabled().performClick()

        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { testDb.db.taskDao().byId(taskId)?.title } == "Comprar pão integral"
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Salvar").fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == true
        }
        compose.onNodeWithText("Salvar").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("salvar não fecha o detalhe", 0, backs) }
    }

    @Test
    fun `voltar sem alteracao fecha a tela`() {
        compose.onNodeWithContentDescription("Voltar").performClick()

        compose.runOnIdle { assertEquals(1, backs) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
