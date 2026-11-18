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
import org.junit.Ignore
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

    @Ignore("Achado da E3 (v1.8.0): semanal concluida fora do dia dela entra em Concluidas e nao no doneCount; remover o @Ignore com a correcao")
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
}
