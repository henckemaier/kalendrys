package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Aba Tarefas vazia em paisagem (v1.9.0, N-4; ADR-0002, 15.7): o preenchimento da casca, com a folga
 * do FAB, vai POR DENTRO da rolagem do vazio. Rolando até o fim, "Nova tarefa" do vazio aparece
 * inteira e acima do FAB (que também diz "Nova tarefa"), com fonte 1.0 e 2.0.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskListEmptyLandscapeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private val requests = mutableListOf<LocalDate>()

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = testDb.close()

    private fun show(fontScale: Float) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = testDb.db
        val repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        val viewModel = testDb.track(TaskViewModel(repository, ShoppingRepository(db), testUndoOffers(testDb, tasks = repository)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    TaskListScreen(onOpenTask = {}, onOpenSettings = {}, onNewTask = { requests += it }, viewModel = viewModel)
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Nenhuma tarefa", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertActionReachable(fontScale: Float) {
        show(fontScale)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()

        // O FAB pode estar recolhido (só a descrição); a ação do vazio tem o texto.
        val newTask = (hasText("Nova tarefa") or hasContentDescription("Nova tarefa")) and hasClickAction()
        val nodes = compose.onAllNodes(newTask).fetchSemanticsNodes()
        assertEquals("FAB e ação do vazio: ${nodes.map { it.config }}", 2, nodes.size)
        // O FAB fica no canto de baixo; a ação do vazio é a de cima.
        val (action, fab) = nodes.sortedBy { it.boundsInRoot.top }
        assertTrue("ação do vazio (${action.boundsInRoot}) sob o FAB (${fab.boundsInRoot})", action.boundsInRoot.bottom <= fab.boundsInRoot.top + 0.5f)
        compose.onNode(newTask and SemanticsMatcher("ação do vazio") { it.id == action.id }).assertIsDisplayed().performClick()
        assertEquals(listOf(LocalDate.now()), requests)
    }

    @Test
    fun `paisagem fonte 1_0 - Nova tarefa do vazio alcancada rolando, acima do FAB`() = assertActionReachable(1f)

    @Test
    fun `paisagem fonte 2_0 - Nova tarefa do vazio alcancada rolando, acima do FAB`() = assertActionReachable(2f)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
