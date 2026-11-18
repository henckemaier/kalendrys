package com.kalendrys.app.ui.today

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * Conteúdo da Hoje (ADR-0002, 14.4; v1.8.0, E3): as seções saem do MESMO retrato do widget Resumo
 * ([com.kalendrys.app.domain.TodayOverview]) e os números batem com ele no mesmo instante; as
 * concluídas vêm pela data do registro e podem ser desmarcadas, também em dia passado e com um
 * ViewModel novo (como ao reabrir o app).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayContentViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repos: TodayRepositories
    private val today = LocalDate.now()
    private val zone = ZoneId.systemDefault()

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

    private fun viewModel() = testDb.track(repos.viewModel(DayOfWeek.MONDAY))

    private suspend fun ReceiveTurbine<TodayViewModel.UiState>.awaitState(
        predicate: (TodayViewModel.UiState) -> Boolean,
    ): TodayViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    private fun millisAt(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    /** Um dia com de tudo: atrasada, de hoje sem e com horário, sem data, blocos feito e pendente, e concluídas. */
    private suspend fun aFullDay() {
        val tasks = repos.tasks
        tasks.addTask("Pagar boleto", timing = TaskTiming(date = today.minusDays(2)))
        tasks.addTask("Comprar pão", timing = TaskTiming(date = today))
        tasks.addTask("Ligar para o banco", timing = TaskTiming(date = today, time = LocalTime.of(23, 30)))
        tasks.addTask("Ideias para o fim de semana")
        // Concluídas hoje: uma sem horário, uma com horário (riscada na trilha) e uma sem data.
        tasks.logCompletion(tasks.addTask("Regar as plantas", timing = TaskTiming(date = today)).id)
        tasks.logCompletion(tasks.addTask("Reunião", timing = TaskTiming(date = today, time = LocalTime.of(23, 0))).id)
        tasks.logCompletion(tasks.addTask("Ler o capítulo").id)
        val treino = repos.routines.add(Routine(title = "Treino", startMinute = 7 * 60, durationMinutes = 30))
        repos.routines.add(Routine(title = "Leitura", startMinute = 21 * 60, durationMinutes = 30))
        repos.routines.complete(treino.id, today)
    }

    private fun TodayViewModel.UiState.trailTasks() = timeline.filterIsInstance<DayTimeline.TaskItem>()

    private fun TodayViewModel.UiState.activeBlocks() =
        timeline.filterIsInstance<DayTimeline.Block>().filter { it.isActive }

    @Test
    fun `pendentes da tela batem com o pendingCount do widget no mesmo instante`() = runTest {
        aFullDay()
        val vm = viewModel()
        vm.uiState.test {
            val state = awaitState { it.overview != null && it.timeline.any { item -> item is DayTimeline.Block } }
            val overview = repos.today.current()

            val onScreen = state.overdueTasks.size + state.dayTasks.size + state.noDateTasks.size +
                state.trailTasks().count { !it.done } + state.activeBlocks().count { !it.done }
            assertEquals("pendentes na tela", overview.pendingCount, onScreen)
            assertEquals("o progresso mostra o mesmo número", overview.pendingCount, state.pendingToday)
            // E cada seção no lugar certo.
            assertEquals(listOf("Pagar boleto"), state.overdueTasks.map { it.task.title })
            assertEquals(listOf("Comprar pão"), state.dayTasks.map { it.task.title })
            assertEquals(listOf("Ideias para o fim de semana"), state.noDateTasks.map { it.task.title })
            assertTrue(state.trailTasks().any { it.task.title == "Ligar para o banco" && !it.done })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concluidas na tela batem com o doneCount do widget no mesmo instante`() = runTest {
        aFullDay()
        val vm = viewModel()
        vm.uiState.test {
            val state = awaitState { it.overview != null && it.completedTasks.isNotEmpty() }
            val overview = repos.today.current()

            val onScreen = state.trailTasks().count { it.done } + state.completedTasks.size
            assertEquals("concluídas na tela", overview.doneCount, onScreen)
            assertEquals(listOf("Ler o capítulo", "Regar as plantas"), state.completedTasks.map { it.task.title })
            // A riscada na trilha não se repete em Concluídas.
            assertTrue(state.trailTasks().single { it.task.title == "Reunião" }.done)
            assertEquals("feitos do progresso", overview.doneCount + 1, state.doneToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `desmarcar uma concluida no dia dela, em dia passado e com ViewModel novo`() = runTest {
        val yesterday = today.minusDays(1)
        val id = repos.tasks.addTask("Consulta", timing = TaskTiming(date = yesterday)).id
        repos.tasks.logCompletion(id, completedAt = millisAt(yesterday, LocalTime.of(10, 0)))

        // VM novo lendo do banco (como ao reabrir o app), no dia da conclusão.
        val vm = viewModel()
        vm.select(yesterday)
        vm.uiState.test {
            val state = awaitState { s -> s.selectedDate == yesterday && s.completedTasks.any { it.task.id == id } }
            vm.toggleTask(state.completedTasks.single { it.task.id == id }.task, done = true)
            awaitState { s -> s.selectedDate == yesterday && s.completedTasks.none { it.task.id == id } }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<Long>(), testDb.db.completionLogDao().observeForTask(id).first().map { it.id })
    }

    @Test
    fun `em outro dia a secao do dia tem as pendentes daquele dia e as concluidas dele`() = runTest {
        val tomorrow = today.plusDays(1)
        repos.tasks.addTask("Levar o carro", timing = TaskTiming(date = tomorrow))
        repos.tasks.addTask("Ideias para o fim de semana")
        val vm = viewModel()
        vm.select(tomorrow)
        vm.uiState.test {
            val state = awaitState { it.selectedDate == tomorrow && it.isLoaded }
            assertEquals(null, state.overview)
            assertEquals(listOf("Levar o carro"), state.dayTasks.map { it.task.title })
            // Atrasadas e Sem data são só da data de hoje.
            assertEquals(emptyList<String>(), state.overdueTasks.map { it.task.title } + state.noDateTasks.map { it.task.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tarefa excluida com registro no dia nao aparece em Concluidas`() = runTest {
        val task = repos.tasks.addTask("Apagada", timing = TaskTiming(date = today))
        repos.tasks.logCompletion(task.id)
        repos.tasks.deleteTask(testDb.db.taskDao().byId(task.id)!!)
        val vm = viewModel()
        vm.uiState.test {
            val state = awaitState { it.overview != null }
            assertEquals(emptyList<String>(), state.completedTasks.map { it.task.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- C1 da revisão da E3: tarefa SEM DATA com lembrete para hoje ---------------------------------

    @Test
    fun `o app nao cria tarefa sem data com lembrete - sem data o horario nao vira lembrete`() = runTest {
        // Painel de nova tarefa e editor do detalhe gravam pelo TaskTiming: sem data, nada de lembrete.
        val timing = TaskTiming(date = null, time = LocalTime.of(9, 0))
        assertEquals(null, timing.reminderAt(zone))

        val created = repos.tasks.addTask("Sem data com horário", timing = timing)
        val stored = testDb.db.taskDao().byId(created.id)!!
        assertEquals(null, stored.dueDate)
        assertEquals(null, stored.reminderAt)
    }

    @Test
    fun `sem data com lembrete hoje (backup adulterado) aparece uma vez so, em Sem data`() = runTest {
        // Único jeito de existir: um backup editado à mão (todo backup do app tem a data do lembrete).
        val id = testDb.db.taskDao().insert(
            com.kalendrys.app.data.local.entity.Task(
                title = "Lembrete solto",
                dueDate = null,
                reminderAt = millisAt(today, LocalTime.of(23, 0)),
            ),
        )
        val vm = viewModel()
        vm.uiState.test {
            val state = awaitState { it.overview != null }
            val overview = repos.today.current()

            // Sem data, a tarefa não "acontece" em dia nenhum: nem a trilha nem o retrato a põem no horário.
            assertEquals(emptyList<Long>(), state.trailTasks().map { it.task.id })
            assertEquals(null, overview.pending.single { it.taskId == id }.reminderToday)
            assertEquals(listOf(id), state.noDateTasks.map { it.task.id })
            // Uma vez só: os pendentes da tela batem com o widget.
            val onScreen = state.overdueTasks.size + state.dayTasks.size + state.noDateTasks.size +
                state.trailTasks().count { !it.done } + state.activeBlocks().count { !it.done }
            assertEquals(overview.pendingCount, onScreen)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
