package com.kalendrys.app.ui.routines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Todas as rotinas" (v1.8.0, E2; ADR-0002, 14.4): lista, pausar/retomar, arquivar com Desfazer e filtro. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AllRoutinesViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var repository: RoutineRepository
    private lateinit var undoOffers: UndoOffers

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        alarms = FakeRoutineAlarms(context)
        repository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
        undoOffers = testUndoOffers(testDb, repository)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(AllRoutinesViewModel(repository, undoOffers))

    private suspend fun ReceiveTurbine<AllRoutinesViewModel.UiState>.awaitState(
        predicate: (AllRoutinesViewModel.UiState) -> Boolean,
    ): AllRoutinesViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    /** O reagendamento vem depois da gravação, no mesmo change{}: espera a chamada (tempo real). */
    private suspend fun awaitScheduled(message: String) = withContext(Dispatchers.Default) {
        val done = withTimeoutOrNull(TIMEOUT_MS) { while (alarms.calls.isEmpty()) delay(10) }
        assertTrue(message, done != null)
    }

    private fun routine(title: String, hour: Int, minute: Int = 0, categoryId: Long? = null) =
        Routine(title = title, startMinute = hour * 60 + minute, createdAt = 0L, categoryId = categoryId)

    private val AllRoutinesViewModel.UiState.activeTitles get() = active.map { it.routine.title }
    private val AllRoutinesViewModel.UiState.pausedTitles get() = paused.map { it.routine.title }

    @Test
    fun `lista ativas e pausadas sem as arquivadas, cada grupo por horario e titulo`() = runTest {
        repository.add(routine("Leitura", 21))
        repository.add(routine("Treino", 7))
        repository.add(routine("Alongar", 7))
        repository.pause(repository.add(routine("Violão", 19)))
        repository.pause(repository.add(routine("Piano", 8)))
        repository.archive(repository.add(routine("Antiga", 6)))

        viewModel().uiState.test {
            val state = awaitState { it.isLoaded && it.active.size + it.paused.size == 5 }
            assertEquals(listOf("Alongar", "Treino", "Leitura"), state.activeTitles)
            assertEquals(listOf("Piano", "Violão"), state.pausedTitles)
            assertTrue(state.paused.all { it.isPaused })
            assertTrue(state.hasAny)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `so arquivadas - sem nada a mostrar`() = runTest {
        repository.archive(repository.add(routine("Antiga", 6)))

        viewModel().uiState.test {
            val state = awaitState { it.isLoaded }
            assertEquals(false, state.hasAny)
            assertTrue(state.active.isEmpty() && state.paused.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pausar leva para Pausadas e retomar devolve, sempre reagendando`() = runTest {
        val treino = repository.add(routine("Treino", 7))
        val vm = viewModel()

        vm.uiState.test {
            awaitState { it.activeTitles == listOf("Treino") }
            alarms.reset()
            vm.pause(treino)
            val paused = awaitState { it.pausedTitles == listOf("Treino") }
            assertTrue(paused.active.isEmpty())
            awaitScheduled("pausar reagenda")

            alarms.reset()
            vm.resume(paused.paused.single().routine)
            awaitState { it.activeTitles == listOf("Treino") && it.paused.isEmpty() }
            awaitScheduled("retomar reagenda")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A oferta do app publicada pela exclusão (no escopo do app, tempo real). */
    private suspend fun awaitOffer() = withContext(Dispatchers.Default) {
        withTimeoutOrNull(TIMEOUT_MS) { undoOffers.pending.first { it != null } }
    }

    @Test
    fun `excluir tira da lista, publica a oferta do app e o Desfazer dela devolve a rotina`() = runTest {
        val treino = repository.add(routine("Treino", 7))
        val vm = viewModel()

        vm.uiState.test {
            awaitState { it.activeTitles == listOf("Treino") }
            alarms.reset()
            vm.archive(treino)
            awaitState { it.active.isEmpty() }
            val offer = awaitOffer()
            assertEquals(UndoTarget.DeletedRoutine(treino.id), offer?.target)
            assertTrue(db.routineDao().byId(treino.id)!!.archivedAt != null)
            awaitScheduled("excluir reagenda")

            alarms.reset()
            undoOffers.undo(offer!!).join()
            awaitState { it.activeTitles == listOf("Treino") }
            assertNull(db.routineDao().byId(treino.id)!!.archivedAt)
            awaitScheduled("desfazer reagenda")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `desfazer a exclusao de uma pausada a devolve pausada`() = runTest {
        val violao = repository.add(routine("Violão", 19))
        repository.pause(violao)
        val vm = viewModel()

        vm.uiState.test {
            val paused = awaitState { it.pausedTitles == listOf("Violão") }.paused.single().routine
            vm.archive(paused)
            awaitState { it.paused.isEmpty() }
            undoOffers.undo(awaitOffer()!!).join()
            val back = awaitState { it.pausedTitles == listOf("Violão") }
            assertTrue(back.active.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sem categorias v1_10_0 - rotina com categoria antiga aparece como as outras, sem filtro`() = runTest {
        // A coluna e a tabela ficam no banco (16.10.3), mas a tela não as usa mais (16.10.5).
        val academia = db.routineCategoryDao().insert(RoutineCategory(name = "Academia", color = "GREEN"))
        repository.add(routine("Treino", 7, categoryId = academia))
        repository.pause(repository.add(routine("Corrida", 6, categoryId = academia)))
        repository.add(routine("Inglês", 20, categoryId = 999L))
        repository.add(routine("Sem categoria", 12))
        val vm = viewModel()

        vm.uiState.test {
            val all = awaitState { it.isLoaded && it.active.size == 3 }
            assertEquals(listOf("Treino", "Sem categoria", "Inglês"), all.activeTitles)
            assertEquals(listOf("Corrida"), all.pausedTitles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
