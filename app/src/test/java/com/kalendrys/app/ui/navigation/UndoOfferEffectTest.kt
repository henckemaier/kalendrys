package com.kalendrys.app.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.deletedTaskTarget
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.clockOf
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.components.showUndo
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.CoroutineScope
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
 * Coletor da oferta no NavHost (v1.8.0, E0-D), com a tela de origem num AppScaffold: a mensagem
 * aparece na tela, Desfazer restaura a tarefa certa e convive com a mensagem da própria tela. NB-4:
 * a oferta é estado; recriar o coletor (girar a tela) com oferta pendente mostra a mensagem de novo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class UndoOfferEffectTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: TaskRepository
    private lateinit var routines: RoutineRepository
    private lateinit var offers: UndoOffers

    /** Relógio da validade da oferta (o tempo da mensagem é o mainClock do Compose). */
    private var now = 0L

    // Escopo do "app" para o restauro, drenado pelo testDb.close() antes de fechar o banco.
    private lateinit var appScope: CoroutineScope
    private var generation by mutableIntStateOf(0)
    private var present by mutableStateOf(true)
    private val own = SnackbarHostState()

    // Escopo da composição (no relógio do teste), como o da tela que mostra a mensagem dela.
    private lateinit var screenScope: CoroutineScope

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
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
        offers = UndoOffers(repository, routines, appScope, clockOf { now })
        compose.setContent {
            KalendrysTheme {
                // Tirar e pôr de volta (com geração nova) descarta o coletor, o estado da mensagem e a tela,
                // como girar: o antigo some antes de o novo nascer.
                if (present) key(generation) {
                    // Como o KalendrysNavHost: um estado do app, fornecido, e o coletor único.
                    val app = remember { SnackbarHostState() }
                    screenScope = rememberCoroutineScope()
                    UndoOfferEffect(offers, app)
                    CompositionLocalProvider(LocalAppSnackbarHostState provides app) {
                        AppScaffold(snackbarHostState = own) {}
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        testDb.close()
    }

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

    /** Como girar a tela: o coletor e o estado da mensagem saem da composição e voltam novos. */
    private fun recreate(whileAway: () -> Unit = {}) {
        // Com o relógio parado, a escrita só entra na composição depois de avisada (sendApplyNotifications)
        // e de um quadro; sem isso a troca atrasava ~1 s e a mensagem antiga podia vencer no meio.
        compose.runOnUiThread {
            present = false
            Snapshot.sendApplyNotifications()
        }
        frames(3)
        assertEquals("o coletor antigo saiu", 0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        whileAway()
        compose.runOnUiThread {
            generation++
            present = true
            Snapshot.sendApplyNotifications()
        }
        frames(3)
        advance()
    }

    private fun frames(count: Int) = repeat(count) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * Espera o restauro avançando quadros: com o relógio parado, a continuação do coletor depois do
     * toque em Desfazer só roda quando o relógio anda.
     */
    private fun waitRestored(id: Long) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            stored(id) != null
        }
    }

    @Test
    fun `oferta mostra Tarefa excluida e Desfazer devolve a tarefa`() {
        val task = deleted("Pagar conta")

        offers.offer(deletedTaskTarget(task))
        advance()

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        waitRestored(task.id)
        assertEquals(task, stored(task.id))
        assertNull("Desfazer limpa a oferta", offers.pending.value)
    }

    @Test
    fun `sem Desfazer a tarefa continua excluida`() {
        val task = deleted("Pagar conta")

        offers.offer(deletedTaskTarget(task))
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        advance(11_000)

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        assertNull(stored(task.id))
        assertNull("tempo esgotado limpa a oferta", offers.pending.value)
    }

    // --- NB-4: girar a tela com a mensagem visível -------------------------------------------------

    @Test
    fun `girar com a mensagem visivel mostra de novo e Desfazer depois restaura`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()

        recreate()

        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        waitRestored(task.id)
        assertEquals(task, stored(task.id))
        assertNull(offers.pending.value)
    }

    @Test
    fun `ao voltar o tempo da mensagem recomeca`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()
        advance(5_000) // ~6 s dos 10 s

        recreate()
        advance(7_000) // ~8 s da nova; sem recomeçar seriam ~14 s, já vencida

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
    }

    @Test
    fun `tempo esgotado limpa e nao reaparece ao girar de novo`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()
        advance(11_000)
        assertNull(offers.pending.value)

        recreate()
        advance(1_000)

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        assertNull(stored(task.id))
    }

    @Test
    fun `oferta nova durante a mensagem troca a mensagem e limpar a antiga nao apaga a nova`() {
        val first = deleted("Primeira")
        val second = deleted("Segunda")

        offers.offer(deletedTaskTarget(first))
        advance()
        val firstOffer = offers.pending.value!!
        offers.offer(deletedTaskTarget(second))
        advance()
        val secondOffer = offers.pending.value!!

        assertEquals(1, compose.onAllNodesWithText("Desfazer").fetchSemanticsNodes().size)
        // A mensagem da antiga terminando (ex.: um coletor atrasado) não apaga a nova.
        offers.finish(firstOffer)
        assertEquals(secondOffer, offers.pending.value)

        compose.onNodeWithText("Desfazer").performClick()
        waitRestored(second.id)
        assertNull(stored(first.id))
        assertNull(offers.pending.value)
    }

    @Test
    fun `oferta do app e a mensagem da propria tela, cada Desfazer desfaz a sua`() {
        val task = deleted("Excluída pelo detalhe")
        offers.offer(deletedTaskTarget(task))
        advance()
        // A tela à frente mostra a mensagem dela dentro dos 10 s (ex.: a Hoje, que segue com a própria;
        // a lista de Tarefas passou para a oferta do app na E5).
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

    // --- Validade da oferta (A1 da revisão do NB-4): 30 s a partir da criação ---

    @Test
    fun `oferta pendente com o coletor recriado depois do prazo nao mostra nada e some`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()

        // Saiu do app pelo voltar com a mensagem na tela e voltou depois do prazo.
        recreate { now += UndoOffers.VALIDITY_MS + 1 }

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        assertNull(offers.pending.value)
        assertNull(stored(task.id))
    }

    @Test
    fun `dentro do prazo o coletor recriado mostra de novo`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()

        // Faltando 5 s (NB-E1-A: a mensagem recriada vive só o que resta do prazo, e o recriar avança 1 s).
        recreate { now += UndoOffers.VALIDITY_MS - 5_000 }

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        assertEquals(task, (offers.pending.value?.target as? UndoTarget.DeletedTask)?.task)
    }

    @Test
    fun `recriada perto do fim do prazo, a mensagem dura so o que resta e limpa a oferta`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()

        recreate { now += UndoOffers.VALIDITY_MS - 5_000 }
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        // ~4 s depois de reaparecer (sem o limite, a mensagem longa de 10 s ainda estaria na tela).
        advance(4_500)

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        assertNull(offers.pending.value)
        assertNull(stored(task.id))
    }

    @Test
    fun `Desfazer tocado com a mensagem visivel restaura, sem conferir o prazo no toque`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()

        // O relógio da oferta já passou do prazo, mas a mensagem ainda está na tela: o toque vale.
        now += UndoOffers.VALIDITY_MS + 1
        compose.onNodeWithText("Desfazer").performClick()

        waitRestored(task.id)
        assertEquals(task, stored(task.id))
        assertNull(offers.pending.value)
    }

    // --- P8: a oferta tem dois tipos; o texto sai do tipo, quem restaura é o objeto ---

    private fun deletedRoutine(title: String): Long = runBlocking {
        val routine = routines.add(Routine(title = title, startMinute = 420, createdAt = TEST_CREATED_AT))
        routines.archive(routine)
        routine.id
    }

    private fun archivedAt(routineId: Long): Long? = runBlocking { testDb.db.routineDao().byId(routineId)?.archivedAt }

    @Test
    fun `rotina excluida mostra Rotina excluida e Desfazer a devolve`() {
        val routineId = deletedRoutine("Treino")

        offers.offer(UndoTarget.DeletedRoutine(routineId))
        advance()

        compose.onNodeWithText("Rotina excluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            archivedAt(routineId) == null
        }
        assertNull(offers.pending.value)
    }

    @Test
    fun `tarefa concluida mostra Tarefa concluida e Desfazer apaga o registro`() {
        val task = runBlocking { repository.addTask("Pagar conta") }
        val logId = runBlocking { repository.logCompletion(task.id) }

        offers.offer(UndoTarget.CompletedTask(logId))
        advance()

        compose.onNodeWithText("Tarefa concluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            runBlocking { testDb.db.completionLogDao().byId(logId) } == null
        }
        assertNull(offers.pending.value)
    }

    @Test
    fun `excluir tarefa e logo depois rotina - a segunda mensagem substitui a primeira e Desfazer restaura so a rotina`() {
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()
        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()

        val routineId = deletedRoutine("Treino")
        offers.offer(UndoTarget.DeletedRoutine(routineId))
        advance()

        assertEquals(0, compose.onAllNodesWithText("Tarefa excluída").fetchSemanticsNodes().size)
        compose.onNodeWithText("Rotina excluída").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Desfazer").fetchSemanticsNodes().size)

        compose.onNodeWithText("Desfazer").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            archivedAt(routineId) == null
        }
        assertNull("a tarefa substituída continua excluída", stored(task.id))
    }

    @Test
    fun `excluir rotina e logo depois tarefa - Desfazer restaura so a tarefa`() {
        val routineId = deletedRoutine("Treino")
        offers.offer(UndoTarget.DeletedRoutine(routineId))
        advance()
        val task = deleted("Pagar conta")
        offers.offer(deletedTaskTarget(task))
        advance()

        compose.onNodeWithText("Tarefa excluída").assertIsDisplayed()
        compose.onNodeWithText("Desfazer").performClick()
        waitRestored(task.id)

        assertEquals(true, archivedAt(routineId) != null)
    }

    @Test
    fun `Desfazer e sair na hora - o coletor some logo depois do toque e a rotina volta mesmo assim`() {
        val routineId = deletedRoutine("Treino")
        offers.offer(UndoTarget.DeletedRoutine(routineId))
        advance()

        compose.onNodeWithText("Desfazer").performClick()
        // Um quadro: o coletor recebe o toque e pede o restauro ao objeto. Logo em seguida sai da
        // composição (tela fechando), com o restauro ainda em voo no escopo do app.
        frames(1)
        compose.runOnUiThread {
            present = false
            Snapshot.sendApplyNotifications()
        }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            archivedAt(routineId) == null
        }
        assertNull(offers.pending.value)
    }
}
