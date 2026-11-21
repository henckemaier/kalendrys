package com.kalendrys.app.ui.taskdetail

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Detalhe da tarefa com salvar na hora (v1.9.0, ADR-0002 15.4; E2): sem "Salvar" nem "Descartar
 * alterações?"; um nó por campo; título vazio como erro; ação principal Concluir ou, concluída,
 * "Desfazer conclusão" (P6); "Reverter alterações" só no menu e só com algo mexido; "Excluir tarefa"
 * no menu com rótulo, com a confirmação de sempre.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskDetailShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: TaskRepository
    private var viewModel: TaskDetailViewModel? = null
    private var backs = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
    }

    @After
    fun tearDown() {
        // N7: as gravações já pedidas terminam antes de fechar o banco.
        runBlocking { withTimeoutOrNull(TIMEOUT_MS) { viewModel?.awaitPendingWrites() } }
        // B2: o fim do ViewModel (o que o onCleared faz), antes de fechar o banco.
        compose.runOnIdle { viewModel?.finish() }
        testDb.close()
    }

    private fun show(task: Task = Task(title = "Comprar pão"), completed: Boolean = false): Long {
        val db = testDb.db
        val id = runBlocking {
            db.taskDao().insert(task).also { if (completed) repository.logCompletion(it) }
        }
        val vm = testDb.track(
            TaskDetailViewModel(
                SavedStateHandle(mapOf(TASK_ID_ARG to id)),
                repository,
                ShoppingRepository(db),
                testUndoOffers(testDb, tasks = repository),
                testDb.appScope(),
            ),
        )
        viewModel = vm
        compose.setContent { KalendrysTheme { TaskDetailScreen(onBack = { backs++ }, viewModel = vm) } }
        waitFor { compose.onAllNodesWithText(task.title).fetchSemanticsNodes().isNotEmpty() }
        return id
    }

    /** O Room volta ao Main a cada consulta: drena o looper enquanto espera. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
        condition()
    }

    private fun storedTitle(id: Long) = runBlocking { testDb.db.taskDao().byId(id)?.title }

    private val titleField get() = compose.onNode(hasSetTextAction() and hasText("Título"))

    /** O cartão de situação fica mais abaixo na lista (LazyColumn): rola até ele para compô-lo. */
    private fun scrollToStatus() = compose.onNode(hasScrollToKeyAction()).performScrollToKey("status")

    private fun openMenu() = compose.onNodeWithContentDescription("Mais opções").performClick()

    @Test
    fun `sem Salvar, um no por campo e o menu sem Reverter antes de mexer`() {
        show()
        compose.onNodeWithText("Detalhes da tarefa").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Salvar").fetchSemanticsNodes().size)
        listOf("Título", "Observações (opcional)").forEach { label ->
            assertEquals("um nó editável para $label", 1, compose.onAllNodes(hasSetTextAction() and hasText(label)).fetchSemanticsNodes().size)
        }

        openMenu()
        compose.onNode(hasText("Excluir tarefa") and hasClickAction()).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Reverter alterações").fetchSemanticsNodes().size)
    }

    @Test
    fun `mudar o titulo grava sem Salvar e liga o Reverter`() {
        val id = show()

        titleField.performTextReplacement("Comprar pão integral")
        // A espera de 500 ms do buffer roda no looper principal: avança o relógio dele.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        waitFor { storedTitle(id) == "Comprar pão integral" }
        openMenu()
        compose.onNode(hasText("Reverter alterações") and hasClickAction()).assertIsDisplayed().performClick()
        waitFor { storedTitle(id) == "Comprar pão" }
        assertEquals("gravar não fecha o detalhe", 0, backs)
    }

    @Test
    fun `titulo vazio aparece como erro, nao grava e o voltar fecha com o ultimo valido`() {
        val id = show()

        titleField.performTextReplacement("")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertExists()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        compose.onNodeWithContentDescription("Voltar").performClick()
        runBlocking { viewModel!!.awaitPendingWrites() }
        compose.runOnIdle { assertEquals(1, backs) }
        assertEquals("Comprar pão", storedTitle(id))
    }

    @Test
    fun `pendente mostra Concluir`() {
        show()
        scrollToStatus()
        compose.onNode(hasText("Concluir") and hasClickAction()).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Desfazer conclusão").fetchSemanticsNodes().size)
    }

    @Test
    fun `concluida mostra Desfazer conclusao e o toque volta a pendente`() {
        val id = show(completed = true)
        scrollToStatus()

        compose.onNode(hasText("Desfazer conclusão") and hasClickAction()).assertIsDisplayed().performClick()

        waitFor { runBlocking { testDb.db.completionLogDao().all() }.none { it.taskId == id } }
        waitFor { compose.onAllNodes(hasText("Concluir") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `Excluir tarefa no menu com rotulo pede confirmacao`() {
        show()
        assertEquals(0, compose.onAllNodesWithText("Excluir tarefa").fetchSemanticsNodes().size)

        openMenu()
        compose.onNodeWithText("Excluir tarefa").performClick()

        compose.onNodeWithText("Excluir tarefa?").assertIsDisplayed()
    }

    @Test
    fun `voltar sem alteracao fecha a tela`() {
        show()
        compose.onNodeWithContentDescription("Voltar").performClick()

        compose.runOnIdle { assertEquals(1, backs) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
