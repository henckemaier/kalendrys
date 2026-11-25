package com.kalendrys.app.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lista de tarefas (ADR-0002, 14.6; v1.8.0, E5): busca, chips e o vazio da busca rolam junto com as
 * tarefas. Fecha os limites da 12.4/13.9 em paisagem com fonte 2.0 (nenhuma tarefa visível no 1º
 * quadro; busca cortada). O campo de busca focado continua focado quando a lista filtra ao digitar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
// Medida de texto de verdade: com fonte 2.0 a busca e os chips crescem como no aparelho.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskListScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TaskViewModel
    private val titles = listOf("Comprar pão", "Pagar a conta de luz", "Ligar para o dentista", "Levar o carro")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        runBlocking { titles.forEach { db.taskDao().insert(Task(title = it)) } }
        viewModel = testDb.track(
            TaskViewModel(
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock),
                ShoppingRepository(db),
                testUndoOffers(testDb),
                clock = SystemAppClock,
            ),
        )
    }

    @After
    fun tearDown() = testDb.close()

    private fun show(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme { TaskListScreen(onOpenTask = {}, onOpenSettings = {}, viewModel = viewModel) }
            }
        }
        waitFor { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private val list get() = compose.onNode(hasScrollToKeyAction())
    private val search: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction())

    /** Visível por inteiro: a parte recortada pelos pais ([SemanticsNode.boundsInRoot]) é o nó todo. */
    private fun assertWhole(label: String, node: SemanticsNode) {
        val visible = node.boundsInRoot
        assertEquals("$label: altura visível", node.size.height.toFloat(), visible.height, 0.5f)
        assertEquals("$label: largura visível", node.size.width.toFloat(), visible.width, 0.5f)
    }

    @Test
    fun `paisagem com fonte 2_0 - busca inteira e a lista rola ate as tarefas`() {
        show(2f)

        search.assertIsDisplayed()
        assertWhole("busca", search.fetchSemanticsNode())
        // A busca e os chips rolam com a lista: rolando, a primeira tarefa aparece inteira.
        list.performScrollToNode(hasText(titles.first()))
        val first = compose.onNode(hasText(titles.first())).assertIsDisplayed()
        assertWhole("primeira tarefa", first.fetchSemanticsNode())
    }

    @Test
    fun `paisagem com fonte 2_0 - a busca rola para fora e volta`() {
        show(2f)

        list.performScrollToNode(hasText(titles.last()))
        compose.onNode(hasText(titles.last())).assertIsDisplayed()
        list.performScrollToKey(TASK_SEARCH_KEY)
        search.assertIsDisplayed()
    }

    @Test
    fun `digitar na busca filtra a lista e o campo continua focado`() {
        show(2f)

        search.performClick()
        search.assertIsFocused()
        search.performTextInput("pão")
        waitFor { compose.onAllNodesWithText("Pagar a conta de luz").fetchSemanticsNodes().isEmpty() }

        // A lista mudou embaixo do campo (as outras tarefas saíram) e o foco ficou nele.
        search.assertIsFocused()
        assertTrue(compose.onAllNodes(hasText("Comprar pão")).fetchSemanticsNodes().isNotEmpty())

        // E até o vazio da busca: a lista troca as seções pelo vazio sem tirar o foco.
        search.performTextInput("zzz")
        waitFor { compose.onAllNodesWithText("Nenhuma tarefa encontrada").fetchSemanticsNodes().isNotEmpty() }
        search.assertIsFocused()
    }

    @Test
    fun `paisagem com fonte 2_0 - vazio da busca rola ate a mensagem`() {
        show(2f)

        search.performClick()
        search.performTextInput("nada assim existe")
        waitFor { compose.onAllNodesWithText("Nenhuma tarefa encontrada").fetchSemanticsNodes().isNotEmpty() }

        list.performScrollToNode(hasText("Tente outro termo ou ajuste os filtros."))
        val message = compose.onNode(hasText("Tente outro termo ou ajuste os filtros.")).assertIsDisplayed()
        assertWhole("mensagem do vazio", message.fetchSemanticsNode())
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun `retrato com fonte 1_0 - busca, chips e tarefas aparecem no primeiro quadro`() {
        show(1f)

        search.assertIsDisplayed()
        assertWhole("busca", search.fetchSemanticsNode())
        // Os chips também (sem tarefa atrasada, "Atrasadas" só existe como chip).
        compose.onNode(hasText("Atrasadas")).assertIsDisplayed()
        // Sem rolar: as tarefas cabem na tela (como antes).
        titles.forEach { compose.onNode(hasText(it)).assertIsDisplayed() }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("busca no topo da tela", search.fetchSemanticsNode().boundsInRoot.top < root.height / 4)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
