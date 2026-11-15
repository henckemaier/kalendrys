package com.kalendrys.app.ui.routines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Progresso 3/8 da lista no bloco da trilha (ADR-0001, P8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutinesViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var routineRepository: RoutineRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var shoppingRepository: ShoppingRepository

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
        )
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        shoppingRepository = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(
        RoutinesViewModel(
            routineRepository,
            taskRepository,
            RoutineCategoryRepository(db),
            DayOfWeek.MONDAY,
            shoppingRepository,
        ),
    )

    private suspend fun ReceiveTurbine<RoutinesViewModel.UiState>.awaitState(
        predicate: (RoutinesViewModel.UiState) -> Boolean,
    ): RoutinesViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    private fun RoutinesViewModel.UiState.block(title: String): DayTimeline.Block =
        timeline.filterIsInstance<DayTimeline.Block>().single { it.routine.title == title }

    @Test
    fun `bloco com lista mostra o progresso e acompanha as marcacoes`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 1, priceCents = null)
        shoppingRepository.add(listId, "Feijão", quantity = 1, priceCents = null)
        routineRepository.add(Routine(title = "Feira", startMinute = 600, shoppingListId = listId))

        viewModel().uiState.test {
            var state = awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }
            assertEquals(ShoppingProgress(0, 2), state.progressFor(state.block("Feira").routine.shoppingListId))

            val arroz = shoppingRepository.observeItems(listId).first().first { it.name == "Arroz" }
            shoppingRepository.setPicked(arroz, picked = true)
            state = awaitState { it.progressFor(listId) == ShoppingProgress(1, 2) }
            assertEquals(ShoppingProgress(1, 2), state.progressFor(state.block("Feira").routine.shoppingListId))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bloco sem lista, com lista vazia ou vinculo orfao nao mostra progresso`() = runTest {
        val empty = shoppingRepository.createList("Vazia")
        routineRepository.add(Routine(title = "Sem lista", startMinute = 480))
        routineRepository.add(Routine(title = "Lista vazia", startMinute = 540, shoppingListId = empty))
        routineRepository.add(Routine(title = "Órfã", startMinute = 600, shoppingListId = 999))

        viewModel().uiState.test {
            val state = awaitState { it.isLoaded && it.timeline.filterIsInstance<DayTimeline.Block>().size == 3 }
            assertNull(state.progressFor(state.block("Sem lista").routine.shoppingListId))
            assertNull(state.progressFor(state.block("Lista vazia").routine.shoppingListId))
            assertNull(state.progressFor(state.block("Órfã").routine.shoppingListId))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
