package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeAppClock
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Virada da meia-noite na aba Tarefas (v1.10.0, ADR-0002 16.3; fecha o limite da 15.9): a data da aba e
 * a consulta dos registros do dia saem do MESMO relógio. Com a aba aberta, cruzar a meia-noite troca o
 * dia sozinho: "Concluídas hoje" esvazia (o registro de ontem não conta hoje) e o que vence no dia novo
 * passa para "Hoje", sem nenhuma outra mudança no banco.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskListMidnightTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var shopping: ShoppingRepository

    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Segunda, 7 de janeiro de 2030 (sem horário de verão em 2030), 23:50. */
    private val monday = LocalDate.of(2030, 1, 7)
    private val clock = FakeAppClock.at(monday.atTime(23, 50), zone)

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock)
        shopping = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        testDb.track(TaskViewModel(tasks, shopping, testUndoOffers(testDb, tasks = tasks), clock))

    private suspend fun ReceiveTurbine<TaskViewModel.UiState>.awaitState(
        predicate: (TaskViewModel.UiState) -> Boolean,
    ): TaskViewModel.UiState {
        var state = awaitItem()
        while (!(state.isLoaded && predicate(state))) state = awaitItem()
        return state
    }

    private fun List<TaskViewModel.Item>.titles() = map { it.task.title }

    @Test
    fun `a aba vira o dia sozinha - concluidas de ontem saem e o que vence hoje entra`() = runTest {
        val daily = tasks.addTask(
            "Remédio",
            timing = TaskTiming(date = monday, time = LocalTime.of(8, 0), repeat = ReminderRepeat.DAILY),
        )
        tasks.addTask("Conta", timing = TaskTiming(date = monday.plusDays(1)))
        tasks.logCompletion(daily.id, completedAt = clock.nowMillis() - 10 * 60_000L)

        viewModel().uiState.test {
            val before = awaitState { it.doneToday.isNotEmpty() }
            assertEquals(monday, before.today)
            assertEquals(listOf("Remédio"), before.doneToday.titles())
            assertTrue("a diária concluída hoje não fica em Hoje", "Remédio" !in before.dueToday.titles())
            assertEquals(listOf("Conta"), before.upcoming.filter { it.task.title == "Conta" }.titles())

            // 00:00:30 de terça: só o relógio anda.
            clock.setLocal(monday.plusDays(1).atTime(0, 0, 30))

            val after = awaitState { it.today == monday.plusDays(1) }
            assertTrue("o registro de ontem não conta hoje", after.doneToday.isEmpty())
            assertEquals(setOf("Remédio", "Conta"), after.dueToday.titles().toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `antes da meia-noite nada muda`() = runTest {
        val daily = tasks.addTask(
            "Remédio",
            timing = TaskTiming(date = monday, time = LocalTime.of(8, 0), repeat = ReminderRepeat.DAILY),
        )
        tasks.logCompletion(daily.id, completedAt = clock.nowMillis())

        viewModel().uiState.test {
            awaitState { it.doneToday.isNotEmpty() }
            clock.setLocal(monday.atTime(23, 59, 59))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
