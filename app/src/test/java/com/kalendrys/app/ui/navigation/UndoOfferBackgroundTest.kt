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
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.clockOf
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.CoroutineScope
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
 * tela (Home, tela apagada): a tela de origem para de desenhar a mensagem (só a tela RESUMED desenha)
 * e o coletor, que segue o mesmo ciclo de vida (o da Activity, no NavHost), para de esperar sem limpar
 * a oferta (A1 do Esquadro). Ao voltar, confere o prazo pelo relógio de parede: dentro dele a mensagem
 * reaparece com o tempo cheio; vencido, é limpa sem mostrar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class UndoOfferBackgroundTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: TaskRepository
    private lateinit var routines: RoutineRepository
    private lateinit var offers: UndoOffers
    private var now = 0L
    // Escopo do "app" para o restauro, drenado pelo testDb.close() antes de fechar o banco.
    private lateinit var appScope: CoroutineScope

    /** Ciclo de vida da tela de origem (no app, o da entrada da navegação, que segue a Activity). */
    private val screen = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        appScope = testDb.appScope()
        val db = testDb.db
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock)
        routines = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
        offers = UndoOffers(repository, routines, appScope, clockOf { now })
        screen.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            KalendrysTheme {
                // Como o KalendrysNavHost: o coletor fica fora da tela; a tela de origem desenha. Os dois
                // seguem o mesmo ciclo de vida (no app, o da Activity; a entrada da navegação o acompanha).
                val app = remember { SnackbarHostState() }
                CompositionLocalProvider(
                    LocalAppSnackbarHostState provides app,
                    LocalLifecycleOwner provides screen,
                ) {
                    UndoOfferEffect(offers, app)
                    AppScaffold {}
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
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
        offers.offer(UndoTarget.DeletedTask(task))
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
        offers.offer(UndoTarget.DeletedTask(task))
        advance()

        lifecycle(Lifecycle.State.CREATED)
        now += 5_000
        lifecycle(Lifecycle.State.RESUMED)

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
    }

    @Test
    fun `tela apagada horas com o relogio acordado parado - ao voltar nao mostra e limpa`() {
        val task = deleted("Pagar conta")
        offers.offer(UndoTarget.DeletedTask(task))
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()

        // Tela apagada: o tempo acordado (o do Compose) quase não anda, o de parede anda 3 h.
        lifecycle(Lifecycle.State.CREATED)
        assertEquals("sair da frente não limpa a oferta", task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
        now += 3 * 60 * 60 * 1_000L
        lifecycle(Lifecycle.State.RESUMED)

        assertEquals("vencida pelo relógio de parede, não reaparece", 0, shown())
        assertNull("e é limpa ao voltar", offers.pending.value)
        assertNull("sem Desfazer a tarefa continua excluída", runBlocking { testDb.db.taskDao().byId(task.id) })
    }

    @Test
    fun `fora da frente a espera para sem limpar e dentro do prazo mostra de novo com Desfazer`() {
        val task = deleted("Pagar conta")
        offers.offer(UndoTarget.DeletedTask(task))
        advance()

        lifecycle(Lifecycle.State.CREATED)
        // Fora da frente, mesmo passando os 10 s da mensagem longa, nada termina a oferta.
        advance(15_000)
        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
        now += 20_000
        lifecycle(Lifecycle.State.RESUMED)

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            runBlocking { testDb.db.taskDao().byId(task.id) } != null
        }
        assertNull(offers.pending.value)
    }

    @Test
    fun `oferta feita com a Activity fora da frente aparece ao voltar dentro do prazo`() {
        // Excluir e apertar Home no mesmo instante: a oferta chega com o coletor já parado.
        lifecycle(Lifecycle.State.CREATED)
        val task = deleted("Pagar conta")
        offers.offer(UndoTarget.DeletedTask(task))
        advance(5_000)
        assertEquals("fora da frente ninguém mostra nem limpa", 0, shown())
        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)

        now += 10_000
        lifecycle(Lifecycle.State.RESUMED)

        // O coletor de dentro nasce com a oferta já pendente e a mostra (dentro dos 30 s).
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
    }
}
