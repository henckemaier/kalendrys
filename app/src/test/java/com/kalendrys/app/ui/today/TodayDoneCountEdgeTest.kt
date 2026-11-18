package com.kalendrys.app.ui.today

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Casos de borda da igualdade "Concluídas da Hoje = doneCount do widget" (ADR-0002, 14.4): a seção
 * entra pela data do REGISTRO, o widget conta pelo status da tarefa no dia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayDoneCountEdgeTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repos: TodayRepositories
    private val today = LocalDate.now()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        repos = TodayRepositories(context, testDb.db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private suspend fun ReceiveTurbine<TodayViewModel.UiState>.awaitState(
        predicate: (TodayViewModel.UiState) -> Boolean,
    ): TodayViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    @Test
    fun `semanal concluida hoje fora do dia dela - Concluidas e doneCount do widget batem`() = runTest {
        // Semanal no dia da semana de amanhã (não acontece hoje), concluída hoje pela aba Tarefas.
        val weekly = repos.tasks.addTask("Tirar o lixo", timing = TaskTiming(date = today.plusDays(1), repeat = ReminderRepeat.WEEKLY))
        repos.tasks.logCompletion(weekly.id)
        val vm = testDb.track(repos.viewModel(DayOfWeek.MONDAY))
        vm.uiState.test {
            val state = awaitState { it.overview != null }
            val overview = repos.today.current()

            val onScreen = state.timeline.filterIsInstance<DayTimeline.TaskItem>().count { it.done } + state.completedTasks.size
            assertEquals(
                "Concluídas ${state.completedTasks.map { it.task.title }} x doneCount do widget",
                overview.doneCount,
                onScreen,
            )
            assertEquals("feitos do progresso x itens em Concluídas", state.completedTasks.size, state.doneToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- NB-E3-A: os dois lados (tela e retrato do widget) com a mesma definição ---

    /** Tarefas feitas na tela (riscadas na trilha + Concluídas) e no retrato, depois de [arrange]. */
    private fun bothSides(expected: Int, arrange: suspend () -> Unit) = runTest {
        arrange()
        val vm = testDb.track(repos.viewModel(DayOfWeek.MONDAY))
        vm.uiState.test {
            val state = awaitState { it.overview != null }
            val onScreen = state.timeline.filterIsInstance<DayTimeline.TaskItem>().count { it.done } + state.completedTasks.size
            assertEquals("tela", expected, onScreen)
            assertEquals("retrato do widget", expected, repos.today.current().doneCount)
            assertEquals("progresso da Hoje", expected, state.doneToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repetida de amanha concluida hoje conta 1 na tela e no retrato`() = bothSides(1) {
        val daily = repos.tasks.addTask("Remédio", timing = TaskTiming(date = today.plusDays(1), repeat = ReminderRepeat.DAILY))
        repos.tasks.logCompletion(daily.id)
    }

    @Test
    fun `unica reaberta para outra data conta 1 na tela e no retrato`() = bothSides(1) {
        val task = repos.tasks.addTask("Levar o carro", timing = TaskTiming(date = today.plusDays(1)))
        repos.tasks.logCompletion(task.id)
        // Concluída adiantada (a data veio para hoje) e depois mudada para +3: reabre.
        val current = testDb.db.taskDao().byId(task.id)!!
        repos.tasks.updateTask(current.copy(dueDate = today.plusDays(3).toEpochDay()))
    }

    @Test
    fun `excluida conta 0 na tela e no retrato`() = bothSides(0) {
        val task = repos.tasks.addTask("Pagar conta")
        repos.tasks.logCompletion(task.id)
        repos.tasks.deleteTask(testDb.db.taskDao().byId(task.id)!!)
    }

    @Test
    fun `duas conclusoes hoje contam 1 na tela e no retrato`() = bothSides(1) {
        val daily = repos.tasks.addTask("Remédio", timing = TaskTiming(date = today, repeat = ReminderRepeat.DAILY))
        val now = System.currentTimeMillis()
        repos.tasks.logCompletion(daily.id, completedAt = now - 2_000)
        repos.tasks.logCompletion(daily.id, completedAt = now - 1_000)
    }

    @Test
    fun `conclusao retroativa gravada ontem nao conta hoje, nos dois lados`() = bothSides(1) {
        val zone = java.time.ZoneId.systemDefault()
        val yesterdayNoon = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val retro = repos.tasks.addTask("Ontem", timing = TaskTiming(date = today.minusDays(3), repeat = ReminderRepeat.DAILY))
        repos.tasks.logCompletion(retro.id, completedAt = yesterdayNoon)
        val todayOne = repos.tasks.addTask("Hoje", timing = TaskTiming(date = today.minusDays(3), repeat = ReminderRepeat.DAILY))
        repos.tasks.logCompletion(todayOne.id)
    }
}
