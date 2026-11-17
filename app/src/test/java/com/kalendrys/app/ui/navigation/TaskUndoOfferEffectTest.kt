package com.kalendrys.app.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.TaskUndoOffers
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.components.showUndo
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coletor das ofertas no NavHost (v1.8.0, E0-D), com a tela de origem num AppScaffold: a mensagem
 * aparece na tela, Desfazer restaura a tarefa certa e convive com a mensagem da própria tela.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TaskUndoOfferEffectTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: TaskRepository
    private lateinit var offers: TaskUndoOffers
    private val own = SnackbarHostState()

    // Escopo da composição (no relógio do teste), como o da tela que mostra a mensagem dela.
    private lateinit var screenScope: CoroutineScope

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        offers = TaskUndoOffers(repository, CoroutineScope(Dispatchers.Main))
        compose.setContent {
            KalendrysTheme {
                // Como o KalendrysNavHost: um estado do app, fornecido, e o coletor único.
                val app = remember { SnackbarHostState() }
                screenScope = rememberCoroutineScope()
                TaskUndoOfferEffect(offers, app)
                CompositionLocalProvider(LocalAppSnackbarHostState provides app) {
                    AppScaffold(snackbarHostState = own) {}
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() = testDb.close()

    // Relógio parado (a mensagem longa não vence sozinha). Escrita de estado fora do quadro (corrotina no
    // Main) só recompõe no quadro seguinte: drena o looper e avança de novo.
    private fun advance(ms: Long = 1_000) {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(ms / 2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun deleted(title: String): Task = runBlocking {
        val task = repository.addTask(title)
        repository.deleteTask(task)
        task
    }

    private fun stored(id: Long): Task? = runBlocking { testDb.db.taskDao().byId(id) }

    /** Os botões Desfazer de cima para baixo. */
    private fun undoButtons(): List<SemanticsNodeInteraction> {
        val count = compose.onAllNodesWithText("Desfazer").fetchSemanticsNodes().size
        return (0 until count).map { compose.onAllNodesWithText("Desfazer")[it] }
            .sortedBy { it.fetchSemanticsNode().boundsInRoot.top }
    }

    private fun waitRestored(id: Long) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            stored(id) != null
        }
    }

    @Test
    fun `oferta mostra Tarefa excluida e Desfazer devolve a tarefa`() {
        val task = deleted("Pagar conta")

        offers.offer(task)
        advance()

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        waitRestored(task.id)
        assertEquals(task, stored(task.id))
    }

    @Test
    fun `sem Desfazer a tarefa continua excluida`() {
        val task = deleted("Pagar conta")

        offers.offer(task)
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        advance(11_000)

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        assertNull(stored(task.id))
    }

    @Test
    fun `oferta nova substitui a anterior, que fica excluida`() {
        val first = deleted("Primeira")
        val second = deleted("Segunda")

        offers.offer(first)
        advance()
        offers.offer(second)
        advance()

        assertEquals(1, compose.onAllNodesWithText("Desfazer").fetchSemanticsNodes().size)
        compose.onNodeWithText("Desfazer").performClick()
        waitRestored(second.id)
        assertNull(stored(first.id))
    }

    @Test
    fun `excluir pelo detalhe e concluir outra na lista, cada Desfazer desfaz a sua`() {
        val task = deleted("Excluída pelo detalhe")
        offers.offer(task)
        advance()
        // A lista mostra a mensagem dela dentro dos 10 s.
        val ownResult = screenScope.async { own.showUndo("Tarefa concluída", "Desfazer") }
        advance()

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Tarefa concluída").assertIsDisplayed()
        // A de cima é a do app (exclusão pelo detalhe).
        undoButtons().first().performClick()
        waitRestored(task.id)
        assertTrue("a da lista segue esperando", ownResult.isActive)
        advance()
        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)

        // Sobra só a da lista.
        compose.onNodeWithText("Desfazer").performClick()
        advance()
        assertTrue(runBlocking { ownResult.await() })
    }
}
