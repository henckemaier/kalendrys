package com.kalendrys.app.ui.today

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.repository.AppClock
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.testutil.FakeAppClock
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.clockOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Virada da meia-noite na Hoje (NB-E3-B da v1.8.0): a seleção é ACOMPANHANDO HOJE ou UM DIA ESCOLHIDO,
 * e o hoje vem da mesma fonte de datas do [TodayRepository]: o relógio do app (v1.10.0), aqui um
 * [FakeAppClock] que o teste avança (a virada é o instante cruzando a meia-noite).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayMidnightTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repos: TodayRepositories

    /** Quarta, 18 de novembro de 2026; a semana começa no domingo (15). */
    private val wednesday = LocalDate.of(2026, 11, 18)
    private val clock = FakeAppClock.at(wednesday.atTime(10, 0))

    /** Avança o relógio para as 10:00 de [day]: cruzando a meia-noite, o dia novo sai no fluxo. */
    private fun turnTo(day: LocalDate) = clock.setLocal(day.atTime(10, 0))

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        testDb = ViewModelTestDatabase(ApplicationProvider.getApplicationContext<Context>())
        repos = TodayRepositories(ApplicationProvider.getApplicationContext(), testDb.db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    /** Relógio de parede lido pelo laço de sempre ([AppClock.dates]: lê ao começar e espera; O-4). */
    @Volatile
    private var wallClockDay = wednesday

    private fun viewModel(readsClock: Boolean = false): TodayViewModel {
        val appClock: AppClock = if (readsClock) {
            clockOf { wallClockDay.atTime(10, 0).atZone(clock.zone()).toInstant().toEpochMilli() }
        } else {
            clock
        }
        val today = TodayRepository(repos.tasks, repos.routines, repos.shopping, appClock)
        return testDb.track(TodayViewModel(repos.routines, repos.tasks, DayOfWeek.SUNDAY, repos.shopping, today, repos.undoOffers, clock = appClock))
    }

    private suspend fun ReceiveTurbine<TodayViewModel.UiState>.awaitState(
        predicate: (TodayViewModel.UiState) -> Boolean,
    ): TodayViewModel.UiState {
        var state = awaitItem()
        while (!(state.isLoaded && predicate(state))) state = awaitItem()
        return state
    }

    @Test
    fun `acompanhando hoje, a virada leva sozinha ao dia novo com os numeros do mesmo dia`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitState { it.selectedDate == wednesday && it.isShowingToday }
            turnTo(wednesday.plusDays(1))
            val state = awaitState { it.today == wednesday.plusDays(1) && it.overview?.date == wednesday.plusDays(1) }
            assertEquals(wednesday.plusDays(1), state.selectedDate)
            assertTrue(state.isShowingToday)
            // Os números vêm do retrato do MESMO dia mostrado.
            assertEquals(state.selectedDate, state.overview?.date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `amanha escolhido que vira hoje volta a acompanhar`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitState { it.selectedDate == wednesday }
            vm.select(wednesday.plusDays(1))
            awaitState { it.selectedDate == wednesday.plusDays(1) && !it.isShowingToday }
            turnTo(wednesday.plusDays(1))
            awaitState { it.today == wednesday.plusDays(1) && it.isShowingToday }
            // Voltou a acompanhar: a próxima virada leva junto.
            turnTo(wednesday.plusDays(2))
            val state = awaitState { it.today == wednesday.plusDays(2) }
            assertEquals(wednesday.plusDays(2), state.selectedDate)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dia passado escolhido continua onde esta na virada, com o botao Hoje`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitState { it.selectedDate == wednesday }
            vm.select(wednesday.minusDays(1))
            awaitState { it.selectedDate == wednesday.minusDays(1) }
            turnTo(wednesday.plusDays(1))
            val state = awaitState { it.today == wednesday.plusDays(1) }
            assertEquals(wednesday.minusDays(1), state.selectedDate)
            assertFalse(state.isShowingToday)
            // Tocar em Hoje volta a acompanhar.
            vm.goToToday()
            awaitState { it.selectedDate == wednesday.plusDays(1) && it.isShowingToday }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `escolher hoje na faixa volta a acompanhar`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitState { it.selectedDate == wednesday }
            vm.select(wednesday.plusDays(2))
            awaitState { it.selectedDate == wednesday.plusDays(2) }
            vm.select(wednesday)
            awaitState { it.selectedDate == wednesday && it.isShowingToday }
            turnTo(wednesday.plusDays(1))
            awaitState { it.selectedDate == wednesday.plusDays(1) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `acompanhando hoje, relogio trocado com a tela por tras e a volta a frente levam ao dia novo`() = runTest {
        val vm = viewModel(readsClock = true)
        vm.uiState.test {
            awaitState { it.selectedDate == wednesday && it.isShowingToday }
            // Relógio ou fuso trocado enquanto a Hoje estava por trás; ao voltar (RESUMED) ela relê.
            wallClockDay = wednesday.plusDays(1)
            vm.onResumed()
            val state = awaitState { it.today == wednesday.plusDays(1) && it.overview?.date == wednesday.plusDays(1) }
            assertEquals(wednesday.plusDays(1), state.selectedDate)
            assertTrue(state.isShowingToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dia escolhido continua escolhido quando a volta a frente traz outro dia`() = runTest {
        val vm = viewModel(readsClock = true)
        vm.uiState.test {
            awaitState { it.selectedDate == wednesday }
            vm.select(wednesday.minusDays(1))
            awaitState { it.selectedDate == wednesday.minusDays(1) }
            wallClockDay = wednesday.plusDays(1)
            vm.onResumed()
            val state = awaitState { it.today == wednesday.plusDays(1) }
            assertEquals(wednesday.minusDays(1), state.selectedDate)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `virada na troca de semana leva a faixa para a semana nova`() = runTest {
        val saturday = LocalDate.of(2026, 11, 21)
        turnTo(saturday)
        val vm = viewModel()
        vm.uiState.test {
            val before = awaitState { it.selectedDate == saturday }
            assertEquals(LocalDate.of(2026, 11, 15), before.week.first().date)
            turnTo(saturday.plusDays(1))
            val after = awaitState { it.selectedDate == saturday.plusDays(1) && it.overview?.date == saturday.plusDays(1) }
            assertEquals(LocalDate.of(2026, 11, 22), after.week.first().date)
            assertEquals(after.selectedDate, after.overview?.date)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
