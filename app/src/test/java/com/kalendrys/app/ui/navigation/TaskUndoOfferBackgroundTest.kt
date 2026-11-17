package com.kalendrys.app.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.TaskUndoOffers
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Validade da oferta de Desfazer (A1 da revisão do NB-4) quando o app vai para o fundo SEM recriar a
 * tela (Home, tela apagada): o coletor continua vivo, preso na mensagem, e a tela de origem para de
 * desenhá-la (só a tela RESUMED desenha). Ao voltar, a mensagem reaparece com o tempo cheio.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TaskUndoOfferBackgroundTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: TaskRepository
    private lateinit var offers: TaskUndoOffers
    private var now = 0L
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ciclo de vida da tela de origem (no app, o da entrada da navegação, que segue a Activity). */
    private val screen = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        offers = TaskUndoOffers(repository, appScope) { now }
        screen.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            KalendrysTheme {
                // Como o KalendrysNavHost: o coletor fica fora da tela; a tela de origem desenha.
                val app = remember { SnackbarHostState() }
                TaskUndoOfferEffect(offers, app)
                CompositionLocalProvider(
                    LocalAppSnackbarHostState provides app,
                    LocalLifecycleOwner provides screen,
                ) { AppScaffold {} }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        appScope.cancel()
        testDb.close()
    }

    private fun advance(ms: Long = 1_000) {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(ms / 2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun lifecycle(state: Lifecycle.State) {
        compose.runOnUiThread { screen.registry.currentState = state }
        advance()
    }

    private fun shown(): Int = compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size

    private fun deleted(title: String): Task = runBlocking {
        val task = repository.addTask(title)
        repository.deleteTask(task)
        task
    }

    @Test
    fun `sair pelo Home com a mensagem na tela e voltar depois do prazo nao oferece Desfazer`() {
        val task = deleted("Pagar conta")
        offers.offer(task)
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()

        // Home com a mensagem na tela (a Activity não é recriada) e volta uma hora depois.
        lifecycle(Lifecycle.State.CREATED)
        assertEquals("no fundo a tela não desenha a mensagem", 0, shown())
        now += 60 * 60 * 1_000L
        advance(60_000)
        lifecycle(Lifecycle.State.RESUMED)

        assertEquals("oferta vencida (1 h) não pode reaparecer com Desfazer", 0, shown())
        assertNull(offers.pending.value)
    }

    @Test
    fun `sair pelo Home e voltar dentro do prazo ainda mostra a mensagem`() {
        val task = deleted("Pagar conta")
        offers.offer(task)
        advance()

        lifecycle(Lifecycle.State.CREATED)
        now += 5_000
        lifecycle(Lifecycle.State.RESUMED)

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        assertEquals(task, offers.pending.value?.task)
    }

    @Test
    fun `mensagem pendurada no fundo some ao fim do prazo, sem a tela a frente`() {
        val task = deleted("Pagar conta")
        offers.offer(task)
        advance()

        lifecycle(Lifecycle.State.CREATED)
        // Ainda no fundo: o prazo (30 s desde a oferta) passa sem nenhuma tela desenhar a mensagem.
        now += TaskUndoOffers.VALIDITY_MS
        advance(TaskUndoOffers.VALIDITY_MS)

        assertNull("o fim do prazo limpa a oferta mesmo no fundo", offers.pending.value)
        assertNull("sem Desfazer a tarefa continua excluída", runBlocking { testDb.db.taskDao().byId(task.id) })
        lifecycle(Lifecycle.State.RESUMED)
        assertEquals(0, shown())
    }
}
